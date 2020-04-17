package net.corda.errorPageBuilder

import net.corda.common.logging.errorReporting.ErrorResource
import java.io.File
import java.lang.IllegalArgumentException
import java.net.URLClassLoader
import java.util.*

class ErrorTableGenerator(private val resourceLocation: File,
                          private val locale: Locale) {

    companion object {
        private const val ERROR_CODE_HEADING = "codeHeading"
        private const val ALIASES_HEADING = "aliasesHeading"
        private const val DESCRIPTION_HEADING = "descriptionHeading"
        private const val TO_FIX_HEADING = "toFixHeading"
        private const val ERROR_HEADINGS_BUNDLE = "ErrorPageHeadings"
    }

    private fun getHeading(heading: String) : String {
        val resource = ResourceBundle.getBundle(ERROR_HEADINGS_BUNDLE, locale)
        return resource.getString(heading)
    }

    private fun generateTable() : List<List<String>> {
        val table = mutableListOf<List<String>>()
        val utils = ErrorResourceUtilities(resourceLocation)
        val loader = utils.loaderForResources()
        for (resource in utils.listResources()) {
            val errorResource = ErrorResource.fromLoader(resource, loader, locale)
            table.add(listOf(resource, errorResource.aliases, errorResource.shortDescription, errorResource.actionsToFix))
        }
        return table
    }

    private fun formatTable(tableData: List<List<String>>) : String {
        val headings = listOf(
                getHeading(ERROR_CODE_HEADING),
                getHeading(ALIASES_HEADING),
                getHeading(DESCRIPTION_HEADING),
                getHeading(TO_FIX_HEADING)
        )
        val underlines = headings.map { "-".repeat(it.length) }
        val fullTable = listOf(headings, underlines) + tableData
        return fullTable.joinToString(System.lineSeparator()) { it.joinToString(prefix = "| ", postfix = " |", separator = " | ") }
    }

    fun generateMarkdown() : String {
        if (!resourceLocation.exists()) throw IllegalArgumentException("Directory $resourceLocation does not exist.")
        val tableData = generateTable()
        return formatTable(tableData)
    }
}