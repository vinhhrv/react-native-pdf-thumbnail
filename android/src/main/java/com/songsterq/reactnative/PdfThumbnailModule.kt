package com.songsterq.reactnative

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import com.facebook.react.bridge.*
import java.io.*
import java.util.*


class PdfThumbnailModule(reactContext: ReactApplicationContext) : ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String {
    return "PdfThumbnail"
  }

  @ReactMethod
  fun generate(filePath: String, page: Int, promise: Promise) {
    var parcelFileDescriptor: ParcelFileDescriptor? = null
    var pdfRenderer: PdfRenderer? = null
    try {
      parcelFileDescriptor = getParcelFileDescriptor(filePath)
      if (parcelFileDescriptor == null) {
        promise.reject("FILE_NOT_FOUND", "File $filePath not found")
        return
      }

      pdfRenderer = PdfRenderer(parcelFileDescriptor)
      if (page < 0 || page >= pdfRenderer.pageCount) {
        promise.reject("INVALID_PAGE", "Page number $page is invalid, file has ${pdfRenderer.pageCount} pages")
        return
      }

      val result = renderPage(pdfRenderer, page, filePath)
      promise.resolve(result)
    } catch (ex: IOException) {
      promise.reject("INTERNAL_ERROR", ex)
    } finally {
      pdfRenderer?.close()
      parcelFileDescriptor?.close()
    }
  }

  @RequiresApi(Build.VERSION_CODES.O)
  @ReactMethod
  fun generateWithBase64(base64: String, page: Int, promise: Promise) {
    val data = Base64.getMimeDecoder().decode(base64);
    val file = File.createTempFile("temp", null)
      .also { FileOutputStream(it).write(data) }
    val stream = ByteArrayInputStream(data);
    var parcelFileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    var pdfRenderer: PdfRenderer? = null
    try {
      pdfRenderer = PdfRenderer(parcelFileDescriptor);
      if (page < 0 || page >= pdfRenderer.pageCount) {
        promise.reject("INVALID_PAGE", "Page number $page is invalid, file has ${pdfRenderer.pageCount} pages")
        return
      }

      val result = renderPageBase64(pdfRenderer, page)
      promise.resolve(result)
    } catch (ex: IOException) {
      promise.reject("INTERNAL_ERROR", ex)
    } finally {
      pdfRenderer?.close()
      parcelFileDescriptor?.close()
    }
  }

  @ReactMethod
  fun generateAllPages(filePath: String, promise: Promise) {
    var parcelFileDescriptor: ParcelFileDescriptor? = null
    var pdfRenderer: PdfRenderer? = null
    try {
      parcelFileDescriptor = getParcelFileDescriptor(filePath)
      if (parcelFileDescriptor == null) {
        promise.reject("FILE_NOT_FOUND", "File $filePath not found")
        return
      }

      pdfRenderer = PdfRenderer(parcelFileDescriptor)
      val result = WritableNativeArray()
      for (page in 0 until pdfRenderer.pageCount) {
        result.pushMap(renderPage(pdfRenderer, page, filePath))
      }
      promise.resolve(result)
    } catch (ex: IOException) {
      promise.reject("INTERNAL_ERROR", ex)
    } finally {
      pdfRenderer?.close()
      parcelFileDescriptor?.close()
    }
  }

  private fun getParcelFileDescriptor(filePath: String): ParcelFileDescriptor? {
    val uri = Uri.parse(filePath)
    if (ContentResolver.SCHEME_CONTENT == uri.scheme || ContentResolver.SCHEME_FILE == uri.scheme) {
      return this.reactApplicationContext.contentResolver.openFileDescriptor(uri, "r")
    } else if (filePath.startsWith("/")) {
      val file = File(filePath)
      return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    return null
  }

  private fun renderPage(pdfRenderer: PdfRenderer, page: Int, filePath: String): WritableNativeMap {
    val currentPage = pdfRenderer.openPage(page)
    val width = currentPage.width
    var height = currentPage.height
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    currentPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
    currentPage.close()

    val cutColor = Color.RED;

    for (y in 0 until bitmap.height) {
      val pixel = bitmap.getPixel(width/2, bitmap.height - y - 1)
      if (pixel == cutColor) {
        height = bitmap.height - y - 4
        break
      }
    }

    // Some bitmaps have transparent background which results in a black thumbnail. Add a white background.
    val bitmapWhiteBG = Bitmap.createBitmap(bitmap.width, height, bitmap.config)
    bitmapWhiteBG.eraseColor(Color.WHITE)
    val canvas = Canvas(bitmapWhiteBG)
    canvas.drawBitmap(bitmap, 0f, 0f, null)
    bitmap.recycle()

    val outputFile = File.createTempFile(getOutputFilePrefix(filePath, page), ".png", reactApplicationContext.cacheDir)
    if (outputFile.exists()) {
      outputFile.delete()
    }
    val out = FileOutputStream(outputFile)
    bitmapWhiteBG.compress(Bitmap.CompressFormat.PNG, 0, out)
    bitmapWhiteBG.recycle()
    out.flush()
    out.close()

    val map = WritableNativeMap()
    map.putString("uri", Uri.fromFile(outputFile).toString())
    map.putInt("width", width)
    map.putInt("height", height)
    return map
  }

  @RequiresApi(Build.VERSION_CODES.O)
  private fun renderPageBase64(pdfRenderer: PdfRenderer, page: Int): WritableNativeMap {
    val currentPage = pdfRenderer.openPage(page)
    val width = currentPage.width
    var height = currentPage.height
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    currentPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
    currentPage.close()

    val cutColor = Color.RED;

    for (y in 0 until bitmap.height) {
      val pixel = bitmap.getPixel(width/2, bitmap.height - y - 1)
      if (pixel == cutColor) {
        height = bitmap.height - y - 2
        break
      }
    }

    // Some bitmaps have transparent background which results in a black thumbnail. Add a white background.
    val bitmapWhiteBG = Bitmap.createBitmap(bitmap.width, height, bitmap.config)
    bitmapWhiteBG.eraseColor(Color.WHITE)
    val canvas = Canvas(bitmapWhiteBG)
    canvas.drawBitmap(bitmap, 0f, 0f, null)
    bitmap.recycle()

    val out = ByteArrayOutputStream();
    bitmapWhiteBG.compress(Bitmap.CompressFormat.PNG, 0, out)
    bitmapWhiteBG.recycle()
    out.flush()
    out.close()
    val base64 = Base64.getMimeEncoder().encodeToString(out.toByteArray());
    val map = WritableNativeMap()
    map.putString("base64", base64)
    map.putInt("width", width)
    map.putInt("height", height)
    return map
  }

  private fun getOutputFilePrefix(filePath: String, page: Int): String {
    val tokens = filePath.split("/")
    val originalFilename = tokens[tokens.lastIndex]
    val prefix = originalFilename.replace(".", "-")
    val generator = Random()
    val random = generator.nextInt(Integer.MAX_VALUE)
    return "$prefix-thumbnail-$page-$random"
  }
}
