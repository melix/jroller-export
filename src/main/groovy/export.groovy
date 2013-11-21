import groovy.text.SimpleTemplateEngine
import org.htmlcleaner.*

import java.text.SimpleDateFormat
import java.util.regex.Pattern

def reformatDate(String date) {
    def sdf = new SimpleDateFormat("MMM dd, yyyy", Locale.US)
    sdf.lenient = true
    def parsed = sdf.parse(date)
    sdf = new SimpleDateFormat('yyyy-MM-dd')
    sdf.format(parsed)
}

def processPage(String url) {
    def cleaner = new HtmlCleaner()
    def props = cleaner.properties
    props.translateSpecialEntities = true
    def serializer = new SimpleHtmlSerializer(props)
    def page = cleaner.clean(url.toURL())
    def metadata = [:].withDefault { '' }

    page.traverse { TagNode tagNode, htmlNode ->
        if (!metadata.contents && tagNode?.name=='div' && tagNode?.attributes?.class == 'postentry') {
            metadata.contents = tagNode
        }
        // tags
        // <p class="meta">Tags:  <a href="http://www.jroller.com/melix/tags/enseignement">enseignement</a> <a href="http://www.jroller.com/melix/tags/informatique">informatique</a></p>
        if (!metadata.tags && tagNode?.name=='p' && tagNode?.attributes?.class=='meta' && tagNode.text.toString().startsWith('Tags')) {
            metadata.tags = (tagNode.text - 'Tags:').trim().split(' ')
        }
        // next page
        if (!metadata.next && tagNode?.name=='div' && tagNode?.attributes?.class=='next-previous' && tagNode.text.toString().contains('&raquo;')) {
            def links = tagNode.childTagList.findAll { it.name == 'a' }
            metadata.next = links[-1].attributes.href
        }
        // date
        // <p class="meta">01:42PM Jul 26, 2007 in category <u>Java</u> by Cédric Champeau</p>
        if (!metadata.publishedDate && tagNode?.name=='p' && tagNode?.attributes?.class=='meta') {
            def m = tagNode.text =~ /(?:[0-9]{2}:[0-9]{2}[AP]M )(.*) in category/
            if (m.find()) {
                metadata.publishedDate = reformatDate(m.group(1))
            }
        }
        // title
        if (!metadata.title && tagNode?.name=='h2' && tagNode?.attributes?.class=='storytitle') {
            metadata.title = tagNode.text.toString()
            metadata.id = tagNode?.attributes?.id
        }
        // remove prettyprint class
        if (tagNode?.attributes?.class=='prettyprint') {
            def children = tagNode.allChildren.collect {
                if (it instanceof TagNode && it.name=='br') {
                    new ContentNode('\n')
                } else if (it instanceof TagNode) {
                    // probably an error of markup
                    new ContentNode("&lt;$it.name&gt;")
                } else {
                    it
                }
            }
            tagNode.allChildren.clear()
            tagNode.allChildren.addAll(children)

        }
        true
    }

    if (metadata.contents) {
        // convert node to HTML
        def tagNode = metadata.contents
        StringWriter wrt = new StringWriter(tagNode.text.size()*2)
        serializer.write(tagNode, wrt, 'utf-8')
        metadata.contents = wrt.toString()

        def tmpHtml = File.createTempFile('export_', '.html')
        def tmpAdoc = File.createTempFile('export_', '.adoc')
        tmpHtml.write(metadata.contents, 'utf-8')
        "pandoc -f html -t asciidoc --smart --no-wrap --normalize -s $tmpHtml -o ${tmpAdoc}".execute().waitFor()
        metadata.contents = postProcessAsciiDoc(tmpAdoc.getText('utf-8'))
        [tmpHtml,tmpAdoc]*.delete()
    } else {
        println "Unable to find blog post contents for $url"
    }

    metadata
}

/**
 * After conversion with 'pandoc', we still have problems with source code, which is not properly rendered.
 * This method will convert it to a format which is understood by Asciidoctor.
 */
String postProcessAsciiDoc(String markup) {
    def p = Pattern.compile(/(?:code,prettyprint(?:.+?)code,prettyprint)\n(.+?)(?:-{5,})/, Pattern.DOTALL)
    p.matcher(markup).replaceAll '''[source]
----
$1
----
'''
}

def config = new ConfigSlurper().parse(this.class.getResource('config.groovy'))
def outputDir = new File(config.jbake_output, 'content')
def engine = new SimpleTemplateEngine()
def next = config.origin
while (next) {
    def template = engine.createTemplate(this.class.getResource('post.gsp'))
    def md = processPage(next)
    if (md.contents) {
        def rendered = template.make([
                author: config.author,
                *: md
        ])
        def (full,year,month,day) = (md.publishedDate =~ /([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})/)[0]
        def postDir = new File(new File(new File(outputDir, year), month), day)
        postDir.mkdirs()
        def doc = new File(postDir, "${md.id}.adoc")
        rendered.writeTo(doc.newWriter('utf-8'))
        println "Exported $next"
    }
    next = md.next
    // be gentle to JRoller!
    Thread.sleep 1000
}
