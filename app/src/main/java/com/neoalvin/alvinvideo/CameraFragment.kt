package com.neoalvin.alvinvideo

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.video.VideoCapture
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.fragment.app.Fragment
import com.neoalvin.alvinvideo.databinding.FragmentCameraBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.*


private var PERMISSIONS_REQUIRED = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO,
    Manifest.permission.WRITE_EXTERNAL_STORAGE
)

class CameraFragment : Fragment() {
    private lateinit var fragmentCameraBinding: FragmentCameraBinding

    private val mainThreadExecutor by lazy { ContextCompat.getMainExecutor(requireContext()) }

    private lateinit var cameraProvider : ProcessCameraProvider

    private lateinit var preview: Preview

    private lateinit var cameraSelector: CameraSelector

    private lateinit var imageCapture: ImageCapture

    private lateinit var videoCapture: VideoCapture<Recorder>

    private lateinit var imageAnalysis: ImageAnalysis

    private lateinit var previewView: PreviewView

    private var isRecording = false

    private var currentRecording: Recording? = null

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissions ->
            // Handle Permission granted/rejected
            var permissionGranted = true
            permissions.entries.forEach {
                if (it.key in PERMISSIONS_REQUIRED && !it.value)
                    permissionGranted = false
            }
            if (!permissionGranted) {
                Toast.makeText(context, "Permission request denied", Toast.LENGTH_LONG).show()
            }
        }

    private val captureListener = Consumer<VideoRecordEvent> { event ->
        if (event !is VideoRecordEvent.Status)
            isRecording = (event is VideoRecordEvent.Start)

        updateRecordingUi(isRecording)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        if (!hasPermission(requireContext())) {
            activityResultLauncher.launch(PERMISSIONS_REQUIRED)
        }

        fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initUi()
        openCamera()
    }

    private fun initUi() {
        previewView = fragmentCameraBinding.preview
        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        fragmentCameraBinding.capture.apply {
            setOnClickListener {
                takePicture()
            }
        }

        updateRecordingUi(isRecording)

        fragmentCameraBinding.record.apply {
            setOnClickListener {
                if (!isRecording) {
                    startRecording()
                    fragmentCameraBinding.capture.visibility = View.GONE
                } else {
                    val recording = currentRecording
                    recording!!.stop()
                    currentRecording = null
                    fragmentCameraBinding.capture.visibility = View.VISIBLE
                }
            }
        }

        fragmentCameraBinding.gallery.setImageResource(R.mipmap.gallery)
        fragmentCameraBinding.gallery.apply {
            setOnClickListener{
                openGallery()
            }
        }
    }

    private fun updateRecordingUi(state: Boolean) {
        isRecording = state

        if (isRecording) {
            fragmentCameraBinding.record.setImageResource(R.drawable.ic_stop)
        } else {
            fragmentCameraBinding.record.setImageResource(R.drawable.ic_start)
        }
    }

    private fun openCamera() {
        var cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindPreview(cameraProvider)
        }, mainThreadExecutor)
    }

    private fun bindPreview(cameraProvider: ProcessCameraProvider) {
        cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

        preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)  //设置宽高比
            .setTargetRotation(Surface.ROTATION_0)
            .build()

        preview.setSurfaceProvider(previewView.surfaceProvider)

        imageCapture = ImageCapture.Builder()
            .setTargetRotation(requireView().display.rotation)
            .build()

        val recorder = Recorder.Builder().build()
        videoCapture = VideoCapture.withOutput(recorder)

        imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build();

        val useCaseGroup = UseCaseGroup.Builder()
            .addUseCase(preview)
            .addUseCase(imageCapture)
            .addUseCase(videoCapture)
            .addUseCase(imageAnalysis)
            .build()

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(viewLifecycleOwner, cameraSelector, useCaseGroup)
        } catch (e: Exception) {
            Log.e(TAG, "bind preview error: $e")
        }
    }

    private fun takePicture() {
        var path = requireContext().cacheDir.canonicalPath + getFileName("jpeg")
        val outputFileOptions = ImageCapture.OutputFileOptions.Builder(File(path)).build()
        imageCapture.takePicture(outputFileOptions, mainThreadExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    Toast.makeText(requireContext(), "picture saved: $path", Toast.LENGTH_LONG).show()
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(requireContext(), "task picture error: $exception", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    private fun startRecording() {
        var fileName = getFileName("mp4")

        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
        }
        var mediaStoreOutput = MediaStoreOutputOptions.Builder(
            requireActivity().contentResolver,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        currentRecording = videoCapture.output
            .prepareRecording(requireActivity(), mediaStoreOutput)
            .start(mainThreadExecutor, captureListener)
    }

    private fun getFileName(type: String): String {
        var date = Date()
        var simpleDateFormat = SimpleDateFormat(FILENAME_FORMAT)

        return simpleDateFormat.format(date) + ".$type"
    }

    private fun openGallery() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("content://media/internal/images/media"))
        startActivity(intent)
    }

    companion object {
        private const val TAG= "CameraFragment"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

        fun hasPermission(context: Context) = PERMISSIONS_REQUIRED.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}