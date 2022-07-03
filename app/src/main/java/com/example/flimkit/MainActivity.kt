package com.example.flimkit

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.*
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonServiceException
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.internal.StaticCredentialsProvider
import com.amazonaws.regions.Region
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.CannedAccessControlList
import com.example.flimkit.databinding.ActivityMainBinding
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

const val REQUEST_IMAGE_GET = 1

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private var photoUri: Uri? = null
    private var year: String? = null
    private var month: String? = null
    private var day: String? = null
    private var extension = "jpg"

    private var newBitmap: Bitmap? = null

    /*
        onCreate
        Stuff that happens when the app starts
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.viewImage.setOnClickListener { openGallery() }
        binding.btnSave.setOnClickListener {
            GlobalScope.launch {
                saveImageToServer()
            }
        }
        binding.imageDate.setOnClickListener { chooseDate() }
    }

    /*
        show(message)
        Briefly displays the given message at the bottom of the screen
     */
    private fun show(message: String) {
        try {
            runOnUiThread {
                run {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }
        catch (e: Exception) {
            debug("Failed to show toast message: $e")
        }
    }

    /*
        openGallery()
        Allows the user to select an image from their phone
     */
    private fun openGallery() {
        val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
        }
        startActivityForResult(intent, REQUEST_IMAGE_GET)
    }

    /*
        renderImage(bitmap)
        Displays the given bitmap on the screen
     */
    private fun renderImage(bitmap: Bitmap?) {
        if (bitmap == null) {
            show("renderImage called without bitmap")
        }
        else {
            binding.viewImage.setImageBitmap(bitmap)
        }
    }

    /*
        onActivityResult
        Handles what happens after the user selects an image
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_IMAGE_GET ->
                if (resultCode == Activity.RESULT_OK) {
                    photoUri = data?.data ?: return

                    val source = ImageDecoder.createSource(contentResolver, photoUri!!)
                    val drawable = ImageDecoder.decodeDrawable(source)

                    if (drawable is AnimatedImageDrawable) {
                        extension = "gif"
                    }

                    val bitmap: Bitmap = ImageDecoder.decodeBitmap(source)

                    contentResolver.openInputStream(photoUri!!).use {
                        stream ->
                            if (stream != null) {
                                val exif = ExifInterface(stream)
                                val date = exif.getAttribute(ExifInterface.TAG_DATETIME)
                                if (date != null) {
                                    year = date.substring(0, 4)
                                    month = date.substring(5, 7)
                                    day = date.substring(8, 10)
                                    binding.imageDate.text = getString(R.string.image_taken_date, year, month, day)
                                }
                            }
                    }

                    // Create a smaller version of the picture
                    val desiredWidth = 1024
                    val desiredHeight:Int = (bitmap.height * (desiredWidth.toDouble() / bitmap.width)).toInt()
                    newBitmap =
                        Bitmap.createScaledBitmap(bitmap, desiredWidth, desiredHeight, true)

                    renderImage(newBitmap)
                }
        }
    }

    /*
        saveImageToServer()
        Uploads the selected image to the digital ocean spaces server
     */
    private fun saveImageToServer() {
        val input = ensureBitmap()
        if (input == "") { return }

        val accesskey = getString(R.string.access_key)
        val secretkey = getString(R.string.secret_key)
        val credentials = StaticCredentialsProvider(BasicAWSCredentials(accesskey, secretkey))

        // us-east-1 is not the region for digital ocean, but I think we're required to
        // use an actual AWS region
        val client = AmazonS3Client(credentials, Region.getRegion("us-east-1"))
        client.endpoint = getString(R.string.endpoint)

        val bucket = getString(R.string.bucket)
        val file = convertResourceToFile()
        val key = "food/${year}/${month}/${year}-${month}-${day}_${input}.$extension"

        // Before saving the file, we need to check and see if it already exists, and if it does,
        // then we should prompt to user to make sure overwriting it is okay
        //
        // This seems kind of backwards to catch an exception and then proceed, whereas not
        // hitting an exception means we stop, but that's what I was able to get working. Maybe
        // revisit this logic later

        try {
            client.getObjectMetadata(bucket, key)
            runOnUiThread {
                run {
                    val builder = AlertDialog.Builder(this)
                    builder.setMessage("File already exists at $key. Would you like to overwrite?")
                        .setPositiveButton("Yes") { _, _ ->
                            GlobalScope.launch { actuallySaveFile(client, bucket, key, file) }
                        }
                        .setNegativeButton("No") { _, _ -> }
                    builder.show()
                }
            }

        }
        catch (e: AmazonClientException) {
            if (e.toString().contains("Not Found")) {
                actuallySaveFile(client, bucket, key, file)
            }
            else {
                debug("client exception: $e")
            }
        }
        catch (e: Exception) {
            debug("other exception: $e")
        }
    }

    /*
        actuallySaveFile(client, bucket, key, file)
        Saves the file to the digital ocean space
    */
    private fun actuallySaveFile(client: AmazonS3Client, bucket: String, key: String, file: File) {
        try {
            // I guess this returns something, but I'll get a warning if I try to save it to a
            // variable. Maybe look more into that later.
            client.putObject(bucket, key, file)
            client.setObjectAcl(bucket, key, CannedAccessControlList.PublicRead)

            val newImageUrl = getString(R.string.cdn_url) + key

            show("Saved to $newImageUrl")

            // Right now this is being copied to the clipboard as plain text (and not as a URL)
            // but I can probably fancy this up a little so the phone recognizes it as a URL
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip: ClipData = ClipData.newPlainText("a url", newImageUrl)
            clipboard.setPrimaryClip(clip)
        } catch (e: AmazonClientException) {
            debug("client exception: $e")
        } catch (e: AmazonServiceException) {
            debug("service exception: $e")
        } catch (e: Exception) {
            debug("some other exception: $e")
        }
    }

    /*
        convertResourceToFile()
        Saves the selected image bitmap into a temporary file

        Copy pasted and modified from
        https://github.com/br3nt0n/Digital-Ocean-Spaces-Android-Example/blob/master/app/src/main/java/thecloudhub/com/digitaloceanspacesexample/SpacesFileRepository.kt
     */
    private fun convertResourceToFile(): File {
        val exampleFile = File(this.filesDir, "tempfile.jpg")
        exampleFile.createNewFile()

        val fileOutputStream = FileOutputStream(exampleFile)

        if (extension == "gif") {
            val inputStream = contentResolver.openInputStream(photoUri!!)
            inputStream.use { input ->
                fileOutputStream.use { output ->
                    input!!.copyTo(output)
                }
            }
        }
        else {
            val exampleBitmap = newBitmap

            val outputStream = ByteArrayOutputStream()
            exampleBitmap!!.compress(Bitmap.CompressFormat.JPEG, 60, outputStream)
            val exampleBitmapData = outputStream.toByteArray()

            fileOutputStream.write(exampleBitmapData)
            fileOutputStream.flush()
            fileOutputStream.close()
        }

        return exampleFile
    }

    /*
        ensureBitmap()
        Checks to make sure that both an image is selected and text is in the input
        Returns the text input if both of these conditions is true, and returns an empty string
        if not
     */
    private fun ensureBitmap(): String {

        // Make sure a photo has been chosen
        if (newBitmap == null) {
            show("No photo chosen")
            return ""
        }

        // Check for something in the text input
        val input = binding.filenameInputText.text.toString().trim()
        if (input == "") {
            show("No filename given")
            return ""
        }

        // Make sure we have a date
        if (year == null || month == null || day == null) {
            show("No date chosen")
            return ""
        }

        return input
    }

    /*
        saveImage()
        Copies the selected image to a new file on the phone -- NOT ACTUALLY USED
     */
    private fun saveImage() {

        val input = ensureBitmap()
        if (input == "") { return }

        val values = ContentValues()
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/test")
        values.put(MediaStore.Images.Media.DISPLAY_NAME, input)
        values.put(MediaStore.Images.Media.IS_PENDING, true)
        val uri: Uri? =
            contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
            saveImageToStream(newBitmap!!, contentResolver.openOutputStream(uri))
            values.put(MediaStore.Images.Media.IS_PENDING, false)
            contentResolver.update(uri, values, null, null)
        }
        show("Saved an image: ${uri.toString()}")
    }

    /*
        saveImageToStream()
        A helper for saveImage -- NOT ACTUALLY USED
     */
    private fun saveImageToStream(bitmap: Bitmap, outputStream: OutputStream?) {
        if (outputStream != null) {
            try {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
                outputStream.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /*
        chooseDate()
        Makes a datepicker appear, so you can manually set a date for the image
     */
    private fun chooseDate() {
        val datePicker = DatePickerDialog(this)
        datePicker.setOnDateSetListener { _, datePickedYear, datePickedMonth, datePickedDay ->
            year  = String.format("%d", datePickedYear)
            month = String.format("%02d", datePickedMonth+1) // What the heck, this is 0 based?!
            day   = String.format("%02d", datePickedDay)

            binding.imageDate.text = getString(R.string.image_taken_date, year, month, day)
        }
        datePicker.show()
    }

    /*
        debug(message)
        Logs the given message under the "flailing" tag
    */
    private fun debug(message: String) {
        Log.d("flailing", message)
    }
}