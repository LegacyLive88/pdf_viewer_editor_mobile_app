package io.github.legacylive88.simplepdfs.pdfcore.internal

import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSDocument
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSObjectKey
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.io.RandomAccessRead
import com.tom_roush.pdfbox.pdfparser.PDFXRefStream
import com.tom_roush.pdfbox.pdfwriter.COSWriter
import com.tom_roush.pdfbox.pdfwriter.COSWriterXRefEntry
import java.io.OutputStream
import java.util.Collections
import kotlin.math.max

/**
 * Writes a PDF *incremental update*: the original file byte for byte, followed by only the
 * objects that changed. The original content is never rewritten, which is what makes saving
 * "over the same file" safe – an interrupted save can at worst leave a damaged tail behind an
 * intact document.
 *
 * This subclass corrects two things in PdfBox-Android's writer, both found by checking its
 * output with qpdf:
 *
 * 1. **Trailer `/Size`.** For classic cross-reference tables the library sets `/Size` to
 *    *highest object number written in this update + 1*. That is only right when the update
 *    adds new objects. An update that merely rewrites existing ones (for example deleting a
 *    highlight) would shrink `/Size`, and readers are entitled to ignore every object numbered
 *    at or above it – PDFium does. Here `/Size` is always one more than the highest object
 *    number in the whole file.
 *
 * 2. **Cross-reference streams.** For files that use them, the library writes a new
 *    cross-reference stream that lacks the entry for itself. Most readers tolerate that, strict
 *    ones warn. Here the stream is rebuilt with that entry included.
 */
internal class IncrementWriter(
    output: OutputStream,
    source: RandomAccessRead,
    objects: Set<COSDictionary>,
    private val document: COSDocument,
    /** `/Size` of the file the update is appended to. */
    private val baseSize: Long,
) : COSWriter(output, source, objects) {

    private var writingXrefStream = false

    override fun doWriteTrailer(doc: COSDocument) {
        standardOutput.write(TRAILER)
        standardOutput.writeEOL()

        val trailer = doc.trailer
        Collections.sort(xRefEntries)
        // `number` is the highest object number in use: the highest of the original file, raised
        // by every new object this update added.
        trailer.setLong(COSName.SIZE, max(number + 1, baseSize))
        if (!doc.isXRefStream) trailer.removeItem(COSName.XREF_STM)
        trailer.removeItem(COSName.DOC_CHECKSUM)
        trailer.getCOSArray(COSName.ID)?.isDirect = true
        trailer.accept(this)
    }

    override fun doWriteObject(obj: COSBase) {
        if (!writingXrefStream && obj is COSStream && COSName.XREF == obj.getCOSName(COSName.TYPE)) {
            // The library has just built the cross-reference stream for this update and is about
            // to write it at the current position, as the next free object number. Build it
            // again, this time including the entry that describes the stream itself.
            val rebuilt = PDFXRefStream(document)
            for (entry in xRefEntries) rebuilt.addEntry(entry)
            rebuilt.addEntry(COSWriterXRefEntry(standardOutput.pos, obj, COSObjectKey(number + 1, 0)))
            rebuilt.addTrailerInfo(document.trailer)
            rebuilt.setSize(max(number + 2, baseSize))
            writingXrefStream = true
            try {
                super.doWriteObject(rebuilt.stream)
            } finally {
                writingXrefStream = false
            }
            return
        }
        super.doWriteObject(obj)
    }
}
