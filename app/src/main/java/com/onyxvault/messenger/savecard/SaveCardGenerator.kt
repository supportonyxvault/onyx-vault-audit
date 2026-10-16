package com.onyxvault.messenger.savecard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.onyxvault.messenger.OnyxUrls
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ONYX VAULT SaveCard Generator - V1.1
 *
 * Erzeugt eine klappbare Scheckkarte (PDF) fuer Vertrauenspersonen (Partner, Anwalt, Familie).
 * Im Notfall scannt die Person QR1 (URL), oeffnet Trigger-Seite im Browser,
 * scannt QR2 (verschluesselter Payload) ab, Browser entschluesselt und loest Killswitch aus.
 *
 * Verschluesselung: AES-256-GCM mit Token-only Key (K = SHA256(token))
 * Payload: Der bestehende Master-QR String (ONYX_MASTER:appId:base64(...))
 * Token: 16 zufaellige Bytes, pro Karte neu
 *
 * V1.1: PDF komplett offline, keine Server/Browser-Abhaengigkeit zur Erzeugung.
 */
object SaveCardGenerator {

    data class SaveCardResult(
        val pdfFile: File,
        val tokenHex: String,
        val triggerUrl: String
    )

    /**
     * Hauptfunktion: Erzeugt die SaveCard PDF.
     *
     * @param context Android Context
     * @param masterQrString Der Master-QR Payload (Format ONYX_MASTER:appId:cipher)
     * @param preferOnion true = Tor .onion URL in QR1 (Partner braucht Tor Browser),
     *                    false = Clearnet GitHub Pages URL (Partner Standard-Phone-Kamera reicht)
     * @return File der erzeugten PDF, Token und Trigger-URL
     */
    fun createSaveCard(
        context: Context,
        masterQrString: String,
        preferOnion: Boolean = false
    ): SaveCardResult {
        // 1. Token
        val tokenBytes = ByteArray(16)
        SecureRandom().nextBytes(tokenBytes)
        val tokenHex = tokenBytes.joinToString("") { "%02x".format(it) }

        // 2. Key = SHA256(token)
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(tokenBytes)

        // 3. IV + AES-GCM Encryption
        val iv = ByteArray(12)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        val cipherText = cipher.doFinal(masterQrString.toByteArray(Charsets.UTF_8))

        // 4. Combined = iv (12) || cipherText+tag
        val combined = ByteArray(iv.size + cipherText.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)
        val qr2Payload = android.util.Base64.encodeToString(combined, android.util.Base64.NO_WRAP)

        // 5. QR1 URL
        val baseUrl = if (preferOnion) OnyxUrls.TOR_GATE_URL else OnyxUrls.SAVETRIGGER_CLEARNET
        val qr1Url = "$baseUrl#t=$tokenHex"  // AUDIT HIGH-3: Hash statt Query - leakt nicht in Server-Logs/Referer

        // 6. QR Bitmaps
        val qr1Bitmap = generateQRBitmap(qr1Url, 500)
        val qr2Bitmap = generateQRBitmapLowECC(qr2Payload, 800)

        // 7. PDF
        val modeTag = if (preferOnion) "TOR" else "GITHUB"
        val pdfFile = File(context.cacheDir, "ONYX_SaveCard_${modeTag}_${tokenHex.take(8)}.pdf")
        val fallbackUrl = if (preferOnion) "${OnyxUrls.SAVETRIGGER_ONION}#t=$tokenHex" else null
        buildPdf(pdfFile, qr1Bitmap, qr2Bitmap, tokenHex, fallbackUrl)

        return SaveCardResult(pdfFile, tokenHex, qr1Url)
    }

    private fun generateQRBitmapLowECC(text: String, size: Int): Bitmap {
        val hints = mapOf(com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.L, com.google.zxing.EncodeHintType.MARGIN to 2)
        val writer = QRCodeWriter()
        val matrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) for (y in 0 until size) bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        return bitmap
    }

    private fun generateQRBitmap(text: String, size: Int): Bitmap {
        val writer = QRCodeWriter()
        val matrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    /**
     * Baut die klappbare Scheckkarte als PDF (quer, 180x120mm).
     * Links: VORDERSEITE (QR1 + "SCAN MICH")
     * Rechts: RÜCKSEITE (QR2 + "Nach Scan umdrehen")
     */
    private fun buildPdf(outFile: File, qr1: Bitmap, qr2: Bitmap, tokenHex: String, fallbackUrl: String? = null) {
        // A6 landscape ~ 148x105mm. Wir nehmen 180x120mm (509x340 pt bei 72 DPI).
        val pageWidth = 510
        val pageHeight = 340
        val pdf = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = pdf.startPage(pageInfo)
        val canvas = page.canvas

        // Hintergrund dunkel
        val bgPaint = Paint().apply {
            color = Color.rgb(10, 20, 25)
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat(), bgPaint)

        // Trennlinie Mitte
        val linePaint = Paint().apply {
            color = Color.rgb(94, 234, 212)
            strokeWidth = 1.5f
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(5f, 3f), 0f)
        }
        canvas.drawLine(pageWidth / 2f, 10f, pageWidth / 2f, pageHeight - 10f, linePaint)

        val tealPaint = Paint().apply {
            color = Color.rgb(94, 234, 212)
            isAntiAlias = true
        }
        val whitePaint = Paint().apply { color = Color.WHITE; isAntiAlias = true }
        val greyPaint = Paint().apply { color = Color.rgb(200, 200, 200); isAntiAlias = true }
        val redPaint = Paint().apply { color = Color.rgb(239, 68, 68); isAntiAlias = true }

        // ===== VORDERSEITE (linke Hälfte) =====
        val halfW = pageWidth / 2
        // Titel
        tealPaint.textSize = 20f; tealPaint.isFakeBoldText = true; tealPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("ONYX VAULT", halfW / 2f, 28f, tealPaint)
        whitePaint.textSize = 12f; whitePaint.textAlign = Paint.Align.CENTER
        canvas.drawText("SaveCard . Notfall", halfW / 2f, 46f, whitePaint)

        // QR1 (170x170 zentriert)
        val qr1Scaled = Bitmap.createScaledBitmap(qr1, 170, 170, false)
        canvas.drawBitmap(qr1Scaled, (halfW - 170) / 2f, 60f, null)

        // Anleitung
        greyPaint.textSize = 10f; greyPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("1. SCAN mit Phone-Kamera", halfW / 2f, 250f, greyPaint)
        canvas.drawText("Oeffnet Trigger-Seite", halfW / 2f, 265f, greyPaint)

        redPaint.textSize = 9f; redPaint.isFakeBoldText = true; redPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("NUR IM NOTFALL VERWENDEN", halfW / 2f, 300f, redPaint)
        canvas.drawText("Nach Scan: Karte umdrehen", halfW / 2f, 315f, redPaint)
        if (fallbackUrl != null) {
            val fbPaint = Paint().apply { color = Color.rgb(255, 193, 7); isAntiAlias = true; textSize = 6f; textAlign = Paint.Align.CENTER }
            canvas.drawText("Falls .onion down - Fallback-URL:", halfW / 2f, 328f, fbPaint)
            canvas.drawText(fallbackUrl.replace("https://", "").take(55), halfW / 2f, 336f, fbPaint)
        }

        // ===== RÜCKSEITE (rechte Hälfte) =====
        tealPaint.textSize = 14f
        canvas.drawText("RUECKSEITE", pageWidth - halfW / 2f, 28f, tealPaint)

        // QR2 (170x170 zentriert)
        val qr2Scaled = Bitmap.createScaledBitmap(qr2, 170, 170, false)
        canvas.drawBitmap(qr2Scaled, pageWidth - halfW / 2f - 85, 60f, null)

        greyPaint.textSize = 10f
        canvas.drawText("2. SCAN mit Trigger-Seite", pageWidth - halfW / 2f, 250f, greyPaint)
        canvas.drawText("Loest Killswitch automatisch", pageWidth - halfW / 2f, 265f, greyPaint)

        greyPaint.textSize = 7f
        canvas.drawText("Token: ${tokenHex.take(12)}...", pageWidth - halfW / 2f, 315f, greyPaint)

        pdf.finishPage(page)

        FileOutputStream(outFile).use { fos -> pdf.writeTo(fos) }
        pdf.close()
    }
}
