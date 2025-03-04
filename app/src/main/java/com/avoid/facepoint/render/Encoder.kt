package com.avoid.facepoint.render


import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.File
import java.io.IOException


class Encoder {

    private var mWidth = -1
    private var mHeight = -1


//    private var mBitRate = -1


    var mEncoder: MediaCodec? = null
    var mInputSurface: CodecInputSurface? = null
    private var mMuxer: MediaMuxer? = null
    private var mTrackIndex = 0
    private var mMuxerStarted = false
//    private val glTexture=GlTexture()

    private var mBufferInfo: MediaCodec.BufferInfo? = null
    private val handlerThread= HandlerThread("TEST").apply { start() }
    private val handler= Handler(handlerThread.looper)

    fun testEncodeVideoToMp4(context: Context) {
        try {
//            val bit = BitmapFactory.decodeStream(context.assets.open("frames/frame_0.jpg"))
//            mWidth=bit.width
//            mHeight=bit.height

            prepareEncoder(30,1200,1600,EGL14.EGL_NO_CONTEXT)
            handler.post {
                mInputSurface!!.makeCurrent()
            }


//            glTexture.textureBitmap=bit
//            glTexture.onSurfaceCreated()

//            glTexture.onSurfaceChanged(mWidth,mHeight)
            var c = 0
            for (i in 0 until NUM_FRAMES) {

                drainEncoder(false)
                if (c >= 4) c = 0
//                glTexture.textureBitmap=bit
                c++


                //generateSurfaceFrame(i)
//                glTexture.onDrawFrame()

                handler.post {
                    generateSurfaceFrame(i)
                    mInputSurface!!.setPresentationTime(computePresentationTimeNsec(i))
                }


                if (VERBOSE) Log.d(
                    TAG,
                    "sending frame $i to encoder"
                )
                handler.post {
                    mInputSurface!!.swapBuffers()
                }
            }


            drainEncoder(true)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            releaseEncoder()
        }


    }

    fun render() {

    }

    fun onStop() {

    }


    fun prepareEncoder(frameRate:Int, width: Int, height: Int, eglContext: EGLContext) {
        mWidth=width
        mHeight=height

        val bitrate =(BPP * frameRate * width * height).toInt()
        mBufferInfo = MediaCodec.BufferInfo()

        val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height)


        format.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
        )
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        format.setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
        if (VERBOSE) Log.d(
            TAG,
            "format: $format"
        )


        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE)
        mEncoder!!.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mInputSurface = CodecInputSurface(mEncoder!!.createInputSurface(), eglContext)
        mEncoder!!.start()


        val outputPath = File(
            OUTPUT_DIR,
            "test." + width + "x" + height + ".mp4"
        ).toString()
        Log.d(TAG, "output file is $outputPath")


        try {
            mMuxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (ioe: IOException) {
            throw RuntimeException("MediaMuxer creation failed", ioe)
        }

        mTrackIndex = -1
        mMuxerStarted = false
    }


    fun releaseEncoder() {
        if (VERBOSE) Log.d(TAG, "releasing encoder objects")
        if (mInputSurface != null) {
            mInputSurface!!.release()
            mInputSurface = null
        }
        if (mEncoder != null) {
            mEncoder!!.stop()
            mEncoder!!.release()
            mEncoder = null
        }
        if (mMuxer != null) {
            mMuxer!!.stop()
            mMuxer!!.release()
            mMuxer = null
        }
    }

    fun drainEncoder(endOfStream: Boolean) {
        val TIMEOUT_USEC = 10000
        if (VERBOSE) Log.d(
            TAG,
            "drainEncoder($endOfStream)"
        )

        if (endOfStream) {
            if (VERBOSE) Log.d(TAG, "sending EOS to encoder")
            mEncoder!!.signalEndOfInputStream()
        }

        var encoderOutputBuffers = mEncoder!!.outputBuffers
        while (true) {
            val encoderStatus = mEncoder!!.dequeueOutputBuffer(mBufferInfo!!, TIMEOUT_USEC.toLong())
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {

                if (!endOfStream) {
                    break
                } else {
                    if (VERBOSE) Log.d(TAG, "no output available, spinning to await EOS")
                }
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {

                encoderOutputBuffers = mEncoder!!.outputBuffers
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {

                if (mMuxerStarted) {
                    throw RuntimeException("format changed twice")
                }
                val newFormat = mEncoder!!.outputFormat
                Log.d(
                    TAG,
                    "encoder output format changed: $newFormat"
                )


                mTrackIndex = mMuxer!!.addTrack(newFormat)
                mMuxer!!.start()
                mMuxerStarted = true
            } else if (encoderStatus < 0) {
                Log.w(
                    TAG, "unexpected result from encoder.dequeueOutputBuffer: " +
                            encoderStatus
                )

            } else {
                val encodedData = encoderOutputBuffers[encoderStatus]
                    ?: throw RuntimeException(
                        "encoderOutputBuffer " + encoderStatus +
                                " was null"
                    )

                if ((mBufferInfo!!.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {


                    if (VERBOSE) Log.d(TAG, "ignoring BUFFER_FLAG_CODEC_CONFIG")
                    mBufferInfo!!.size = 0
                }

                if (mBufferInfo!!.size != 0) {
                    if (!mMuxerStarted) {
                        throw RuntimeException("muxer hasn't started")
                    }


                    encodedData.position(mBufferInfo!!.offset)
                    encodedData.limit(mBufferInfo!!.offset + mBufferInfo!!.size)

                    mMuxer!!.writeSampleData(mTrackIndex, encodedData, mBufferInfo!!)
                    if (VERBOSE) Log.d(TAG, "sent " + mBufferInfo!!.size + " bytes to muxer")
                }

                mEncoder!!.releaseOutputBuffer(encoderStatus, false)

                if ((mBufferInfo!!.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    if (!endOfStream) {
                        Log.w(TAG, "reached end of stream unexpectedly")
                    } else {
                        if (VERBOSE) Log.d(TAG, "end of stream reached")
                    }
                    break
                }
            }
        }
    }


    fun generateSurfaceFrame(index: Int) {
        var frameIndex = index
        frameIndex %= 8

        val startX: Int
        val startY: Int
        if (frameIndex < 4) {
            // (0,0) is bottom-left in GL
            startX = frameIndex * (mWidth / 4)
            startY = mHeight / 2
        } else {
            startX = (7 - frameIndex) * (mWidth / 4)
            startY = 0
        }

        GLES20.glClearColor(TEST_R0 / 255.0f, TEST_G0 / 255.0f, TEST_B0 / 255.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(startX, startY, mWidth / 4, mHeight / 2)
        GLES20.glClearColor(TEST_R1 / 255.0f, TEST_G1 / 255.0f, TEST_B1 / 255.0f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }


    /**
     * Holds state associated with a Surface used for MediaCodec encoder input.
     *
     *
     * The constructor takes a Surface obtained from MediaCodec.createInputSurface(), and uses
     * that to create an EGL window surface.  Calls to eglSwapBuffers() cause a frame of data to
     * be sent to the video encoder.
     *
     *
     * This object owns the Surface -- releasing this will release the Surface too.
     */
    class CodecInputSurface(surface: Surface?,val eglContext: EGLContext) {
        private var mEGLDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var mEGLContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var mEGLSurface: EGLSurface = EGL14.EGL_NO_SURFACE

        private var mSurface: Surface?

        /**
         * Creates a CodecInputSurface from a Surface.
         */
        init {
            if (surface == null) {
                throw NullPointerException()
            }
            mSurface = surface

            eglSetup()
        }

        /**
         * Prepares EGL.  We want a GLES 2.0 context and a surface that supports recording.
         */
        private fun eglSetup() {
            mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (mEGLDisplay === EGL14.EGL_NO_DISPLAY) {
                throw RuntimeException("unable to get EGL14 display")
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
                throw RuntimeException("unable to initialize EGL14")
            }

            // Configure EGL for recording and OpenGL ES 2.0.
            val attribList = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            EGL14.eglChooseConfig(
                mEGLDisplay, attribList, 0, configs, 0, configs.size,
                numConfigs, 0
            )
            checkEglError("eglCreateContext RGB888+recordable ES2")

            // Configure context for OpenGL ES 2.0.
            val attrib_list = intArrayOf(
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
            )
            mEGLContext = EGL14.eglCreateContext(
                mEGLDisplay, configs[0], eglContext,
                attrib_list, 0
            )
            checkEglError("eglCreateContext")

            // Create a window surface, and attach it to the Surface we received.
            val surfaceAttribs = intArrayOf(
                EGL14.EGL_NONE
            )
            mEGLSurface = EGL14.eglCreateWindowSurface(
                mEGLDisplay, configs[0], mSurface,
                surfaceAttribs, 0
            )
            checkEglError("eglCreateWindowSurface")
        }

        /**
         * Discards all resources held by this class, notably the EGL context.  Also releases the
         * Surface that was passed to our constructor.
         */
        fun release() {
            if (mEGLDisplay !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    mEGLDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT
                )
                EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface)
                EGL14.eglDestroyContext(mEGLDisplay, mEGLContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(mEGLDisplay)
            }
            mSurface!!.release()

            mEGLDisplay = EGL14.EGL_NO_DISPLAY
            mEGLContext = EGL14.EGL_NO_CONTEXT
            mEGLSurface = EGL14.EGL_NO_SURFACE

            mSurface = null
        }

        /**
         * Makes our EGL context and surface current.
         */
        fun makeCurrent() {
            EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)
            checkEglError("eglMakeCurrent")
        }

        /**
         * Calls eglSwapBuffers.  Use this to "publish" the current frame.
         */
        fun swapBuffers(): Boolean {
            val result = EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface)
            checkEglError("eglSwapBuffers")
            return result
        }

        /**
         * Sends the presentation time stamp to EGL.  Time is expressed in nanoseconds.
         */
        fun setPresentationTime(nsecs: Long) {
            EGLExt.eglPresentationTimeANDROID(mEGLDisplay, mEGLSurface, nsecs)
            checkEglError("eglPresentationTimeANDROID")
        }

        /**
         * Checks for EGL errors.  Throws an exception if one is found.
         */
        private fun checkEglError(msg: String) {
            var error: Int
            if ((EGL14.eglGetError().also { error = it }) != EGL14.EGL_SUCCESS) {
                throw RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error))
            }
        }

        companion object {
            private const val EGL_RECORDABLE_ANDROID = 0x3142
        }
    }


    companion object {
        private const val TAG = "EncodeAndMuxTest"
        private const val VERBOSE = true


        private val OUTPUT_DIR: File =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)


        private const val MIME_TYPE = "video/avc"
        private var FRAME_RATE = 3
        private const val IFRAME_INTERVAL = 3
        private const val NUM_FRAMES = 30
        private const val BPP = 0.25f


        private const val TEST_R0 = 0
        private const val TEST_G0 = 136
        private const val TEST_B0 = 0
        private const val TEST_R1 = 236
        private const val TEST_G1 = 50
        private const val TEST_B1 = 186


        fun computePresentationTimeNsec(frameIndex: Int): Long {
            val ONE_BILLION: Long = 1000000000
            return frameIndex * ONE_BILLION / FRAME_RATE
        }
    }
}