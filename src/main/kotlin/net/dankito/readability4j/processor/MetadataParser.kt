package net.dankito.readability4j.processor

import net.dankito.readability4j.model.ArticleMetadata
import net.dankito.readability4j.util.RegExUtil
import org.jsoup.nodes.Document
import java.util.regex.Pattern


open class MetadataParser(protected val regEx: RegExUtil = RegExUtil()): ProcessorBase() {


    private fun unescapeHtmlEntities(str: String?) : String? {
        if (str.isNullOrEmpty()) return str

        // A map to replace HTML entities with their corresponding characters
        val htmlEscapeMap = mapOf(
            "quot" to "\"",
            "amp" to "&",
            "apos" to "'",
            "lt" to "<",
            "gt" to ">"
        )

        // First, replace HTML entities
        val replacedEntities = htmlEscapeMap.entries.fold(str) { acc, (key, value) ->
            acc.replace("&$key;", value)
        }

        // Then, replace numeric character references
        val result = replacedEntities.replace(Regex("&#(?:x([0-9a-fA-F]{1,4})|([0-9]{1,4}));")) { matchResult ->
            val hex = matchResult.groups[1]?.value
            val numStr = matchResult.groups[2]?.value
            val num = hex?.toInt(16) ?: numStr?.toInt() ?: 0
            num.toChar().toString() // Convert to character
        }

        return result
    }

    open fun getArticleMetadata(document: Document): ArticleMetadata {
        val metadata = ArticleMetadata()
        val values = HashMap<String, String>()

        // property is a space-separated list of values
        val propertyPattern = Pattern.compile(
            "\\s*(article|dc|dcterm|og|twitter)\\s*:\\s*(author|creator|description|published_time|title|site_name)\\s*",
            Pattern.CASE_INSENSITIVE
        )

        // name is a single value
        val namePattern = Pattern.compile(
            "^\\s*(?:(dc|dcterm|og|twitter|weibo:(article|webpage))\\s*[.:]\\s*)?(author|creator|description|title|site_name)\\s*$",
            Pattern.CASE_INSENSITIVE
        )

        // Find description tags.
        document.select("meta").forEach { element ->
            val elementName = element.attr("name")
            val elementProperty = element.attr("property")
            val content = element.attr("content")
            if (content.isEmpty()) return@forEach

            var matches = false
            var name: String?
            if (elementProperty.isNotEmpty()) {
                val matcher = propertyPattern.matcher(elementProperty)
                if (matcher.find()) {
                    matches = true
                    // Convert to lowercase, and remove any whitespace
                    // so we can match below.
                    name = matcher.group().lowercase().replace("\\s+".toRegex(), "")
                    // multiple authors
                    values[name] = content.trim()
                }
            }
            if (!matches && elementName.isNotEmpty() && namePattern.matcher(elementName).find()) {
                name = elementName
                if (content.isNotEmpty()) {
                    // Convert to lowercase, remove any whitespace, and convert dots
                    // to colons so we can match below.
                    name =
                        name.lowercase()
                            .replace("\\s+".toRegex(), "")
                            .replace("\\.".toRegex(), ":")
                    values[name] = content.trim()
                }
            }
        }

        // get description
        metadata.excerpt = values["dc:description"] ?:
                values["dcterm:description"] ?:
                values["og:description"] ?:
                values["weibo:article:description"] ?:
                values["weibo:webpage:description"] ?:
                values["description"] ?:
                values["twitter:description"]

        // get title
        metadata.title = values["dc:title"] ?:
                values["dcterm:title"] ?:
                values["og:title"] ?:
                values["weibo:article:title"] ?:
                values["weibo:webpage:title"] ?:
                values["title"] ?:
                values["twitter:title"]

        if(metadata.title.isNullOrBlank()) {
            metadata.title = getArticleTitle(document)
        }

        // get author
        metadata.byline = values["dc:creator"] ?:
                values["dcterm:creator"] ?:
                values["author"]

        metadata.charset = document.charset().name()

        // in many sites the meta value is escaped with HTML entities,
        // so here we need to unescape it
        metadata.title = unescapeHtmlEntities(metadata.title)
        metadata.byline = unescapeHtmlEntities(metadata.byline)
        metadata.excerpt = unescapeHtmlEntities(metadata.excerpt)

        return metadata
    }

    protected open fun getArticleTitle(doc: Document): String {
        var curTitle = ""
        var origTitle = ""

        try {
            origTitle = doc.title()
            curTitle = origTitle

            // If they had an element with id "title" in their HTML
            if(curTitle.isBlank()) {
                doc.select("#title").first()?.let { elementWithIdTitle ->
                    origTitle = getInnerText(elementWithIdTitle, regEx)
                    curTitle = origTitle
                }
            }
        } catch(e: Exception) {/* ignore exceptions setting the title. */}

        var titleHadHierarchicalSeparators = false

        // If there's a separator in the title, first remove the final part
        if(curTitle.contains(" [|\\-/>»] ".toRegex())) {
            titleHadHierarchicalSeparators = curTitle.contains(" [/>»] ".toRegex())
            curTitle = origTitle.replace("(.*)[|\\-/>»] .*".toRegex(RegexOption.IGNORE_CASE), "$1")

            // If the resulting title is too short (3 words or fewer), remove
            // the first part instead:
            if(wordCount(curTitle) < 3) {
                curTitle = origTitle.replace("[^|\\-/>»]*[|\\-/>»](.*)".toRegex(RegexOption.IGNORE_CASE), "$1")
            }
        }
        else if(curTitle.contains(": ")) {
            // Check if we have an heading containing this exact string, so we
            // could assume it's the full title.
            val match = doc.select("h1, h2").filter { it.wholeText() == curTitle }.size > 0

            // If we don't, let's extract the title out of the original title string.
            if(match == false) {
                curTitle = origTitle.substring(origTitle.lastIndexOf(':') + 1)

                // If the title is now too short, try the first colon instead:
                if(wordCount(curTitle) < 3) {
                    curTitle = origTitle.substring(origTitle.indexOf(':') + 1)
                }
                // But if we have too many words before the colon there's something weird
                // with the titles and the H tags so let's just use the original title instead
                else if(wordCount(origTitle.substring(0, origTitle.indexOf(':'))) > 5) {
                    curTitle = origTitle
                }
            }
        }
        else if(curTitle.length > 150 || curTitle.length < 15) {
            val hOnes = doc.getElementsByTag("h1")

            if(hOnes.size == 1) {
                curTitle = getInnerText(hOnes[0], regEx)
            }
        }

        curTitle = curTitle.trim()
        // If we now have 4 words or fewer as our title, and either no
        // 'hierarchical' separators (\, /, > or ») were found in the original
        // title or we decreased the number of words by more than 1 word, use
        // the original title.
        val curTitleWordCount = wordCount(curTitle)
        if(curTitleWordCount <= 4 &&
            (!titleHadHierarchicalSeparators ||
            curTitleWordCount != wordCount(origTitle.replace("[|\\-/>»]+".toRegex(), "")) - 1)) {
            curTitle = origTitle
        }

        return curTitle
    }

    protected open fun wordCount(str: String): Int {
        return str.split("\\s+".toRegex()).size
    }

}