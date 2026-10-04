package de.leaddialer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Made-up data only: this repository is public. */
class ImportTest {

    private fun xlsx(sheetXml: String, shared: List<String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(
                "xl/workbook.xml",
                """<?xml version="1.0"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                   xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                   <sheets><sheet name="Freunde" sheetId="1" r:id="rId1"/></sheets></workbook>"""
            )
            put(
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                   <Relationship Id="rId1" Type="worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""
            )
            put(
                "xl/sharedStrings.xml",
                "<?xml version=\"1.0\"?><sst>" + shared.joinToString("") { "<si><t>$it</t></si>" } + "</sst>"
            )
            put("xl/worksheets/sheet1.xml", sheetXml)
        }
        return out.toByteArray()
    }

    @Test
    fun excelWithTitleRowFirstAndLastNameAndNumericPhones() {
        val shared = listOf("Freunde", "Vorname", "Nachname", "Geburtstage", "Telefon", "Zusatz", "Anna", "Muster", "Freund", "Ben", "+436601234567", "Carla", "Ergebnis", "#REF!")
        val sheet = """<?xml version="1.0"?><worksheet><sheetData>
            <row r="1"><c r="A1" t="s"><v>0</v></c></row>
            <row r="2"><c r="A2" t="s"><v>1</v></c><c r="B2" t="s"><v>2</v></c><c r="C2" t="s"><v>3</v></c><c r="D2" t="s"><v>4</v></c><c r="E2" t="s"><v>5</v></c></row>
            <row r="3"><c r="A3" t="s"><v>6</v></c><c r="B3" t="s"><v>7</v></c><c r="C3"><v>45789</v></c><c r="D3"><v>4.36601112222E11</v></c><c r="E3" t="s"><v>8</v></c></row>
            <row r="4"><c r="A4" t="s"><v>9</v></c><c r="D4" t="s"><v>10</v></c></row>
            <row r="5"><c r="A5" t="s"><v>11</v></c><c r="D5"><v>4915112345678</v></c></row>
            <row r="6"><c r="A6" t="s"><v>12</v></c><c r="C6" t="e"><v>13</v></c></row>
        </sheetData></worksheet>"""
        val leads = CsvIO.parseXlsx(xlsx(sheet, shared))
        assertEquals(listOf("Anna Muster", "Ben", "Carla"), leads.map { it.name })
        assertEquals(listOf("+436601112222", "+436601234567", "+4915112345678"), leads.map { it.phone })
        assertEquals("Freund", leads[0].note)
    }

    @Test
    fun csvPicksPhoneColumnOverDatesAndCustomerNumbers() {
        val csv = """
            Name;Kundennummer;Phone;Datum;Kampagne
            Max Beispiel;10023;+43 660 1234567;27.9.2026;Website
            Eva Probe;10024;0664 7654321;25.9.2026;Website
        """.trimIndent()
        val leads = CsvIO.parse(csv)
        assertEquals(listOf("Max Beispiel", "Eva Probe"), leads.map { it.name })
        assertEquals(listOf("+436601234567", "06647654321"), leads.map { it.phone })
    }

    @Test
    fun csvWithoutHeaderFindsPhoneByContent() {
        val csv = "Max Beispiel,+491701234567\nEva Probe,+491707654321"
        val leads = CsvIO.parse(csv)
        assertEquals(listOf("Max Beispiel", "Eva Probe"), leads.map { it.name })
        assertEquals(listOf("+491701234567", "+491707654321"), leads.map { it.phone })
    }

    @Test
    fun unknownHeadersStillWork() {
        val csv = "Wer;Wie erreichbar\nMax Beispiel;0660 1234567\nEva Probe;0664 7654321"
        val leads = CsvIO.parse(csv)
        assertEquals(listOf("Max Beispiel", "Eva Probe"), leads.map { it.name })
        assertEquals(listOf("06601234567", "06647654321"), leads.map { it.phone })
    }
}
