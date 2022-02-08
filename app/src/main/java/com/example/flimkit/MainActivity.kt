package com.example.flimkit

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
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
            //saveImage()
            GlobalScope.launch {
                saveImageToServer()
            }

        }
    }

    /*
        show(message)
        Briefly displays the given message at the bottom of the screen
     */
    private fun show(message: String) {
        debug("In show")
        try {
            runOnUiThread {
                run {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
    @RequiresApi(Build.VERSION_CODES.N)
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQUEST_IMAGE_GET ->
                if (resultCode == Activity.RESULT_OK) {
                    photoUri = data?.data ?: return

                    val bitmap: Bitmap = MediaStore.Images.Media.getBitmap(contentResolver, photoUri)

                    contentResolver.openInputStream(photoUri!!).use {
                        stream ->
                            if (stream != null) {
                                val exif = ExifInterface(stream)
                                val date = exif.getAttribute(ExifInterface.TAG_DATETIME)
                                year = date?.substring(0, 4)
                                month = date?.substring(5, 7)
                                day = date?.substring(8, 10)
                            }
                    }

                    // Create a smaller version of the picture?
                    val desiredWidth = 1024
                    val desiredHeight:Int = (bitmap.height * (desiredWidth.toDouble() / bitmap.width)).toInt()
                    Log.d("flailing", "Desired height is $desiredHeight")

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

        try {
            val bucket = getString(R.string.bucket)
            val file = convertResourceToFile()
            val key = "food/${year}/${month}/${year}-${month}-${day}_${input}.jpg"

            // I guess this returns something, but I'll get a warning if I try to save it to a
            // variable. Maybe look more into that later.
            client.putObject(bucket, key, file)
            client.setObjectAcl(bucket, key, CannedAccessControlList.PublicRead)

            runOnUiThread {
                run {
                    Toast.makeText(this, "Saved to $key", Toast.LENGTH_SHORT).show()
                }
            }
        }
        catch (e: AmazonClientException) {
            Log.d("flailing", "client exception: $e")
        }
        catch (e: AmazonServiceException) {
            Log.d("flailing", "service exception: $e")
        }
        catch (e: Exception) {
            Log.d("flailing", "some other exception: $e")
        }

    }

    /*
        convertResourceToFile()
        Saves the selected image bitmap into a temporary file

        Copy pasted and modified from
        https://github.com/br3nt0n/Digital-Ocean-Spaces-Android-Example/blob/master/app/src/main/java/thecloudhub/com/digitaloceanspacesexample/SpacesFileRepository.kt
     */
    private fun convertResourceToFile(): File {
        val exampleBitmap = newBitmap

        Log.d("flailing", "${this.filesDir}")

        val exampleFile = File(this.filesDir, "tempfile.jpg")
        exampleFile.createNewFile()

        val outputStream = ByteArrayOutputStream()
        exampleBitmap!!.compress(Bitmap.CompressFormat.JPEG, 100, outputStream)
        val exampleBitmapData = outputStream.toByteArray()

        val fileOutputStream = FileOutputStream(exampleFile)
        fileOutputStream.write(exampleBitmapData)
        fileOutputStream.flush()
        fileOutputStream.close()

        return exampleFile
    }

    /*
        ensureBitmap()
        Checks to make sure that both an image is selected and text is in the input
        Returns the text input if both of these conditions is true, and returns an empty string
        if not
     */
    private fun ensureBitmap(): String {
        debug("In ensureBitmap")

        // Make sure a photo has been chosen
        if (newBitmap == null) {
            debug("No photo chosen")
            show("No photo chosen")
            return ""
        }

        Log.d("flailing","I guess there's a bitmap?")

        // Check for something in the text input
        val input = binding.filenameInputText.text.toString()
        if (input == "") {
            show("No filename given")
            return ""
        }

        return input
    }

    /*
        saveImage()
        Copies the selected image to a new file on the phone
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
        A helper for saveImage
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
        debug(message)
        Logs the given message under the "flailing" tag
     */
    private fun debug(message: String) {
        Log.d("flailing", message)
    }
}