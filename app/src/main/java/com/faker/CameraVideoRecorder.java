package com.faker;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;

public class CameraVideoRecorder {

    public CameraVideoRecorder(Activity mActivity) {
        this.mActivity = mActivity;
    }

    public interface OnButtonUpdateListener {
        void onButtonUpdate(String text);
    }
    private OnButtonUpdateListener buttonUpdateListener;
    private final String LOG_TAG = "faker_log";

    private AutoFitTextureView mTextureView;
    private boolean mIsRecording = false;
    boolean getIsRecording() {
        return mIsRecording;
    }

    private String mNextVideoAbsolutePath;

    private static final int SENSOR_ORIENTATION_DEFAULT_DEGREES = 90;
    private static final int SENSOR_ORIENTATION_INVERSE_DEGREES = 270;

    private static final SparseIntArray DEFAULT_ORIENTATIONS = new SparseIntArray();
    private static final SparseIntArray INVERSE_ORIENTATIONS = new SparseIntArray();

    public void setButtonUpdateListener(OnButtonUpdateListener listener) {
        this.buttonUpdateListener = listener;
    }
    public void updateButton(String text) {
        if (buttonUpdateListener != null) {
            buttonUpdateListener.onButtonUpdate(text);
        }
    }
    private Activity mActivity;
    void setTextureView(AutoFitTextureView mTextureView) {
        this.mTextureView = mTextureView;
    }
    public void startRecording() {
        if (null == mCameraDevice || !mTextureView.isAvailable() || null == mPreviewSize) {
            return;
        }

        closePreviewSession();
        try {
            //1 设置MediaRecorder
            setUpMediaRecorder();

            //2 获取到TextureView的Texture
            SurfaceTexture texture = mTextureView.getSurfaceTexture();
            assert texture != null;

            //3 设置texture默认缓冲大小
            texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());

            //4 初始化CaptureRequest
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            List<Surface> surfaces = new ArrayList<>();

            // Set up Surface for the camera preview
            Surface previewSurface = new Surface(texture);
            surfaces.add(previewSurface);
            mPreviewBuilder.addTarget(previewSurface);

            // Set up Surface for the MediaRecorder
            Surface recorderSurface = mMediaRecorder.getSurface();
            surfaces.add(recorderSurface);
            mPreviewBuilder.addTarget(recorderSurface);

            // Start a capture session
            // Once the session starts, we can update the UI and start recording
            mCameraDevice.createCaptureSession(surfaces, recorderSessionStateCallback, mBackGroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    public void stopRecording() {
        mIsRecording = false;
        // Stop recording
        mMediaRecorder.stop();
        mMediaRecorder.reset();

        Log.d(LOG_TAG, "Video saved: " + mNextVideoAbsolutePath);

        mNextVideoAbsolutePath = null;
        startPreview();
    }


    private MediaRecorder mMediaRecorder;

    private void setUpMediaRecorder() {
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (mNextVideoAbsolutePath == null || mNextVideoAbsolutePath.isEmpty()) {
            mNextVideoAbsolutePath = getVideoFilePath();
        }
        mMediaRecorder.setOutputFile(mNextVideoAbsolutePath);
        mMediaRecorder.setVideoEncodingBitRate(10000000);
        mMediaRecorder.setVideoFrameRate(30);
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(), mVideoSize.getHeight());
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        int rotation = mActivity.getWindowManager().getDefaultDisplay().getRotation();
        switch (mSensorOrientation) {
            case SENSOR_ORIENTATION_DEFAULT_DEGREES:
                mMediaRecorder.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation));
                break;
            case SENSOR_ORIENTATION_INVERSE_DEGREES:
                mMediaRecorder.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation));
                break;
        }
        try {
            mMediaRecorder.prepare();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getVideoFilePath() {
        final File dir = Environment.getExternalStorageDirectory();
        return (dir == null ? "" : (dir.getAbsolutePath() + "/"))
                + "test_record.mp4";
    }

    /**
     * 引用HandlerThread的目的: 通过HandlerThread在子线程中创建的Looper,
     * 来构建Handler,传给CameraCaptureSession。
     */
    private HandlerThread mBackGroundThread;
    private Handler mBackGroundHandler;

    public void startBackgroundThread() {

        mBackGroundThread = new HandlerThread("CameraBackground");
        mBackGroundThread.start();
        mBackGroundHandler = new Handler(mBackGroundThread.getLooper());
    }

    public void stopBackgroundThread() {
        try {
            mBackGroundThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        mBackGroundThread = null;
        mBackGroundHandler = null;
    }

    //预览控件的尺寸
    private Size mPreviewSize;

    //视频数据的尺寸
    private Size mVideoSize;

    private Integer mSensorOrientation;

    @SuppressLint("MissingPermission")
    public void openCamera(int width, int height) {

        //1 初始化CameraManager
        CameraManager cameraManager = (CameraManager) mActivity.getSystemService(Context.CAMERA_SERVICE);

        try {
            String cameraId = cameraManager.getCameraIdList()[0];

            //2 初始化摄像头设备id的属性
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

            //3 获取到摄像头设备属性支持的配置，比如可以获取到所支持的所有窗口大小组合
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            Size[] sizeArray = map.getOutputSizes(MediaRecorder.class);
            for(Size size: sizeArray){
                Log.i(LOG_TAG, "Size Array: " + "width = "+size.getWidth()+", height = "+size.getHeight());
            }

            //4 获取到摄像头的拍摄旋转角度
            mSensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

            //5 根据摄像头所支持的窗口size组合，来选择一个合适的窗口size组合
            mVideoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder.class));

            //mVideoSize.getWidth(): 1440,mVideoSize.getHeight(): 1080
            Log.i(LOG_TAG, "mVideoSize.getWidth(): " + mVideoSize.getWidth() + ",mVideoSize.getHeight(): " + mVideoSize.getHeight());


            //6 选择一组合适的预览的size组合
            mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), width, height, mVideoSize);
//            mPreviewSize = new Size(1920,1080);
            Log.i(LOG_TAG, "mPreviewSize.getWidth(): " + mPreviewSize.getWidth() + ",mPreviewSize.getHeight(): " + mPreviewSize.getHeight());


            //7 设置TextureView宽高比
            int orientation = mActivity.getResources().getConfiguration().orientation;

            if (orientation == Configuration.ORIENTATION_LANDSCAPE) { //横屏
                mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                Log.i(LOG_TAG, "横屏显示");
            } else {
                mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());
                Log.i(LOG_TAG, "竖屏显示");
            }

            //8 配置TextureView的Transform
            configureTransform(width, height);

            //9 初始化MediaRecorder
            mMediaRecorder = new MediaRecorder();

            //10 打开摄像头
            cameraManager.openCamera(cameraId, mStateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == mTextureView || null == mPreviewSize) {
            return;
        }
        int rotation = mActivity.getWindowManager().getDefaultDisplay().getRotation();
        Matrix matrix = new Matrix();
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        RectF bufferRect = new RectF(0, 0, mPreviewSize.getHeight(), mPreviewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / mPreviewSize.getHeight(),
                    (float) viewWidth / mPreviewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        }
        mTextureView.setTransform(matrix);
    }

    private Size chooseVideoSize(Size[] choices) {
        for (Size size : choices) {
            if (size.getWidth() == 1920 && size.getHeight() == 1080) {
                return size;
            }
        }

        return choices[choices.length - 1];
    }

    private static Size chooseOptimalSize(Size[] choices, int width, int height, Size aspectRatio) {
        // Collect the supported resolutions that are at least as big as the preview Surface
        List<Size> bigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getHeight() == option.getWidth() * h / w && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }

        // Pick the smallest of those, assuming we found any
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizeByArea());
        } else {
            return choices[0];
        }
    }

    private CameraDevice mCameraDevice;
    private Semaphore mCameraOpenCloseLock = new Semaphore(1);

    //相机开启，关闭， 异常的状态回调
    private CameraDevice.StateCallback mStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCameraDevice = camera;
            startPreview();
            mCameraOpenCloseLock.release();
            if (null != mTextureView) {
                configureTransform(mTextureView.getWidth(), mTextureView.getHeight());
            }
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            mCameraOpenCloseLock.release();
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            mCameraOpenCloseLock.release();
            mCameraDevice.close();
            mCameraDevice = null;
            mActivity.finish();
        }
    };

    private CaptureRequest.Builder mPreviewBuilder;

    private void startPreview() {

        Log.i(LOG_TAG, "startPreview");
        if (null == mCameraDevice || !mTextureView.isAvailable() || mPreviewSize == null) {
            return;
        }

        closePreviewSession();

        SurfaceTexture texture = mTextureView.getSurfaceTexture();
        assert texture != null;
        texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        try {
            mPreviewBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            Surface previewSurface = new Surface(texture);
            mPreviewBuilder.addTarget(previewSurface);

            mCameraDevice.createCaptureSession(Collections.singletonList(previewSurface), previewSessionStateCallback, mBackGroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private CameraCaptureSession.StateCallback recorderSessionStateCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log.i(LOG_TAG, "onConfigured");

            mPreviewSession = cameraCaptureSession;
            updatePreview();

            mIsRecording = true;
            mMediaRecorder.start();

            mActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    buttonUpdateListener.onButtonUpdate("停止");
                    // UI
//                    mActivity.mButtonStatus.setText("停止");
                }
            });
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log.i(LOG_TAG, "onConfigureFailed");
        }
    };


    private CameraCaptureSession.StateCallback previewSessionStateCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log.i(LOG_TAG, "onConfigured");

            mPreviewSession = cameraCaptureSession;
            updatePreview();
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
            Log.i(LOG_TAG, "onConfigureFailed");
        }
    };

    private CameraCaptureSession mPreviewSession;

    private void updatePreview() {
        if (null == mCameraDevice) {
            return;
        }

        setUpCaptureRequestBuilder(mPreviewBuilder);
        try {
            mPreviewSession.setRepeatingRequest(mPreviewBuilder.build(), null, mBackGroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closePreviewSession() {
        if (mPreviewSession != null) {
            mPreviewSession.close();
            mPreviewSession = null;
        }
    }

    private void setUpCaptureRequestBuilder(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
    }


    static class CompareSizeByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * (long) lhs.getHeight() - rhs.getWidth() * rhs.getHeight());
        }
    }

    public TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            openCamera(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };

}
