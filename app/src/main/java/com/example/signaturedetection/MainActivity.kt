package com.example.signaturedetection;

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
//import android.provider.MediaStore
//import org.opencv.android.OpenCVLoader
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
//import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.google.mlkit.vision.text.Text.Element as TextElement
//import com.google.android.gms.tasks.Tasks
//import org.opencv.core.*
//import org.opencv.android.Utils
//import org.opencv.imgproc.Imgproc
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs



// States for signature detection
sealed class SignatureDetectionState {
    object Idle : SignatureDetectionState()
    object Processing : SignatureDetectionState()
    data class SignatureFound(val locations: List<SignatureLocation>) : SignatureDetectionState()
    object NoSignatureFound : SignatureDetectionState()
    data class Error(val message: String) : SignatureDetectionState()
}

data class SignatureLocation(
    val boundingBox: android.graphics.Rect,
    val confidence: Float,
    val text: String
)



// Function to extract text between two markers
fun extractTextBetween(text: String, startMarker: String, startMarker2: String, endMarker: String, endMarker2: String): String {

    var startIndex = text.indexOf(startMarker, ignoreCase = true)
    if (startIndex == -1) startIndex = text.indexOf(startMarker2, ignoreCase = true)
    if (startIndex == -1) return "Not Found"

    val startPosition = startIndex + startMarker.length
    var endIndex = text.indexOf(endMarker, startPosition, ignoreCase = true)
    if (endIndex == -1) endIndex = text.indexOf(endMarker2, startPosition, ignoreCase = true)
    if (endIndex == -1) return "Not Found"

    return text.substring(startPosition, endIndex).trim()
}

class SignatureDetectionViewModel : ViewModel() {
    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    private val _signatureState = MutableStateFlow<SignatureDetectionState>(SignatureDetectionState.Idle)
    val signatureState: StateFlow<SignatureDetectionState> = _signatureState

    fun detectSignature(bitmap: Bitmap) {
        viewModelScope.launch {
            try {
                _signatureState.value = SignatureDetectionState.Processing

                val image = InputImage.fromBitmap(bitmap, 0)

                textRecognizer.process(image)
                    .addOnSuccessListener { result ->

                        val signatureLocations = mutableListOf<SignatureLocation>()

                        val blocks = result.textBlocks

                        // Extract all text elements with their bounding boxes
                        val textElements = mutableListOf<TextElement>()
                        for (block in blocks) {
                            for (line in block.lines) {
                                for (element in line.elements) {
                                    textElements.add(element)
                                }
                            }
                        }

                        // Group elements by row based on Y-coordinate proximity
                        val rowThreshold = 20 // Adjust based on your image size and text density
                        val rows = mutableListOf<MutableList<TextElement>>()

                        // Sort all elements by Y-coordinate first
                        val sortedElements = textElements.sortedBy { it.boundingBox?.top }

                        var currentRow = mutableListOf<TextElement>()
                        var lastY = -1

                        for (element in sortedElements) {
                            val elementY = element.boundingBox?.centerY()?.toInt() ?: continue

                            if (lastY == -1 || abs(elementY - lastY) <= rowThreshold) {
                                // Same row
                                currentRow.add(element)
                            } else {
                                // New row
                                if (currentRow.isNotEmpty()) {
                                    // Sort the current row by X-coordinate
                                    currentRow.sortBy { it.boundingBox?.left }
                                    rows.add(currentRow)
                                }
                                currentRow = mutableListOf(element)
                            }

                            lastY = elementY
                        }

                        // Add the last row
                        if (currentRow.isNotEmpty()) {
                            currentRow.sortBy { it.boundingBox?.left }
                            rows.add(currentRow)
                        }

                        // Now process each row to get the text in row-wise order
                        for (row in rows) {
                            val rowText = row.joinToString(" ") { it.text }
                            Log.d("ROW_TEXT", rowText)
                        }

                        val allText = rows.joinToString(" ") { row ->
                            row.joinToString(" ") { it.text }
                        }

                        // Extract the name (optional)
                        val extractName = { text: String ->
                            val regex = "Nama\\s*:?\\s*([^T]+)Tempat".toRegex(RegexOption.IGNORE_CASE)
                            val matchResult = regex.find(text)
                            matchResult?.groupValues?.get(1)?.trim() ?: "Name not found"
                        }

                        val extractedName = extractName(allText)
                        val extractedName2= extractTextBetween(allText, "Nama :", "Narna :", "Tem", "Tern")

                        var foundName = false
                        if (extractedName2 != "Not Found") {
                            foundName = true
                            val sl = SignatureLocation(
                                boundingBox = Rect(0, 0, 0, 0),
                                confidence = 1.0f,
                                text = extractedName2
                            )
                            signatureLocations.add(sl)
                        }

                        if (extractedName != "Name not found") {
                            foundName = true
                            val sl = SignatureLocation(
                                boundingBox = Rect(0, 0, 0, 0),
                                confidence = 1.0f,
                                text = extractedName
                            )
                            signatureLocations.add(sl)
                        }

                        Log.d("EXTRACTED_NAME", extractedName)



                        _signatureState.value = if (foundName) {
                            SignatureDetectionState.SignatureFound(signatureLocations)
                        } else {
                            SignatureDetectionState.NoSignatureFound
                        }
                        Log.d("OCR", "Recognized text: ${result.text}")
                    }
                    .addOnFailureListener { e ->
                        Log.e("OCR", "Text recognition failed", e)
                    }



            } catch (e: Exception) {
                _signatureState.value = SignatureDetectionState.Error(e.message ?: "Unknown error")
            }
        }
    }
}



class MainActivity : ComponentActivity() {
    private lateinit var viewFinder: PreviewView
    private lateinit var capturedImage: ImageView
    private lateinit var captureButton: Button
    private lateinit var retakeButton: Button
    private lateinit var analyzeButton: Button
    private lateinit var resultText: TextView
    private lateinit var cameraExecutor: ExecutorService
    private var imageCapture: ImageCapture? = null
    private var capturedBitmap: Bitmap? = null

    private val viewModel: SignatureDetectionViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewFinder = findViewById(R.id.viewFinder)
        capturedImage = findViewById(R.id.capturedImage)
        captureButton = findViewById(R.id.captureButton)
        retakeButton = findViewById(R.id.retakeButton)
        analyzeButton = findViewById(R.id.analyzeButton)
        resultText = findViewById(R.id.resultText)

        if (allPermissionsGranted()) {
            viewFinder = findViewById(R.id.viewFinder)
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        captureButton.setOnClickListener { takePhoto() }
        retakeButton.setOnClickListener {
            viewFinder = findViewById(R.id.viewFinder)
            capturedImage.visibility = View.GONE
            viewFinder.visibility = View.VISIBLE
        }
        analyzeButton.setOnClickListener { analyzeCapturedImage() }

        cameraExecutor = Executors.newSingleThreadExecutor()

        setupObservers()
    }

    private fun setupObservers() {
        lifecycleScope.launch {
            viewModel.signatureState.collect { state ->
                when (state) {
                    is SignatureDetectionState.Idle -> {
                        resultText.text = ""
                    }
                    is SignatureDetectionState.Processing -> {
                        resultText.text = "Analyzing document..."
                    }
                    is SignatureDetectionState.SignatureFound -> {
                        resultText.text = buildString {
                            append("✅ Name found: ")
                            append(state.locations[0].text)
                        }
                        highlightSignatures(state.locations)
                    }
                    is SignatureDetectionState.NoSignatureFound -> {
                        resultText.text = "❌ No name found"
                    }
                    is SignatureDetectionState.Error -> {
                        resultText.text = "Error: ${state.message}"
                    }

                    else -> {}
                }
            }
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get() // ✅ Called inside the listener

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.setSurfaceProvider(viewFinder.surfaceProvider)
                    }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )

            } catch (exc: Exception) {
                Log.e("CameraX", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this)) // ✅ Runs on a background thread
    }


    private fun takePhoto() {
        imageCapture?.takePicture(
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.capacity())
                    buffer.get(bytes)
                    capturedBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

                    viewFinder.visibility = View.GONE
                    capturedImage.visibility = View.VISIBLE
                    capturedImage.setImageBitmap(capturedBitmap)

//                    captureButton.text = "Retake"
                    analyzeButton.visibility = View.VISIBLE

                    image.close()
                }

                override fun onError(exc: ImageCaptureException) {
                    // Handle error
                }
            }
        )
    }

    private fun analyzeCapturedImage() {
        capturedBitmap?.let { bitmap ->
            viewModel.detectSignature(bitmap)
        }
    }

    private fun highlightSignatures(locations: List<SignatureLocation>) {
        capturedBitmap?.let { bitmap ->
            val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(mutableBitmap)
            val paint = Paint().apply {
                color = Color.GREEN
                style = Paint.Style.STROKE
                strokeWidth = 5f
            }

            locations.forEach { location ->
                canvas.drawRect(location.boundingBox, paint)
            }

            capturedImage.setImageBitmap(mutableBitmap)
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}