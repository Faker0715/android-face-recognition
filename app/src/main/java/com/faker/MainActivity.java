package com.faker;


import android.Manifest;
import android.os.Bundle;
import android.os.Handler;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.PermissionChecker;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;


public class MainActivity extends AppCompatActivity implements View.OnClickListener,CameraVideoRecorder.OnButtonUpdateListener   {

    private final String LOG_TAG = "faker_log";

    private ImageView mMaskImage;
    private AutoFitTextureView mTextureView;
    private Button mButtonStatus;
    private Button mButtonFinish;

    private CameraVideoRecorder mCameraVideoRecorder;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        initView();
        initPermission();


        mCameraVideoRecorder = new CameraVideoRecorder(this);
        mCameraVideoRecorder.setTextureView(mTextureView);
        mCameraVideoRecorder.setButtonUpdateListener(this);
    }

    private void initView() {
        mTextureView = findViewById(R.id.texture_view);
        mButtonStatus = findViewById(R.id.btn_status);
        mButtonFinish = findViewById(R.id.btn_finish);
        mMaskImage = findViewById(R.id.mask_img);
        mButtonStatus.setOnClickListener(this);
        mButtonFinish.setOnClickListener(this);
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_status:
                if (mCameraVideoRecorder.getIsRecording()) {
                    mCameraVideoRecorder.stopRecording();
                    mButtonStatus.setText("开始录制");
                } else {
                    mCameraVideoRecorder.startRecording();
                }

                break;
            case R.id.btn_finish:
                finish();
                break;
            default:
                break;
        }
    }



    @Override
    protected void onResume() {
        super.onResume();
        Log.i(LOG_TAG,"onResume");

        mCameraVideoRecorder.startBackgroundThread();
        mCameraVideoRecorder.setInit(false);

        if (mTextureView.isAvailable()) {
            Log.i(LOG_TAG,"mTextureView isAvailable");
            mCameraVideoRecorder.openCamera(mTextureView.getWidth(), mTextureView.getHeight());
        } else {
            mTextureView.setSurfaceTextureListener(mCameraVideoRecorder.mSurfaceTextureListener);
        }
        mCameraVideoRecorder.setInit(true);
    }

    @Override
    protected void onPause() {
        Log.i(LOG_TAG,"onPause");
        super.onPause();
//        mCameraVideoRecorder.stopBackgroundThread();
    }


    private void initPermission() {
        int perMissionCamera = PermissionChecker.checkSelfPermission(this, Manifest.permission.CAMERA);
        int storagePermission = PermissionChecker.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE);
        int recordPermission = PermissionChecker.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);

        if (perMissionCamera == -1 || storagePermission == -1 || recordPermission == -1) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.RECORD_AUDIO}, 1000);
        }
    }

    @Override
    public void onButtonUpdate(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mButtonStatus.setText(text);
            }
        });
    }

}
