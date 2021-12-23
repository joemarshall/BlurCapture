package com.joemarshall.blurcapture;

import android.Manifest;
import android.annotation.SuppressLint;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCaptureSession.StateCallback;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Toast;

import com.joemarshall.blurcapture.databinding.ActivityFullscreenBinding;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An example full-screen activity that shows and hides the system UI (i.e.
 * status bar and navigation/system bar) with user interaction.
 */
public class FullscreenActivity extends AppCompatActivity {
    private static final String LOG_TAG = "BLURCAPTURE";

    private static final String FOLDER_NAME=Environment.DIRECTORY_DCIM + File.separator + "blurcapture"+File.separator;
    /**
     * Whether or not the system UI should be auto-hidden after
     * {@link #AUTO_HIDE_DELAY_MILLIS} milliseconds.
     */
    private static final boolean AUTO_HIDE = true;

    /**
     * If {@link #AUTO_HIDE} is set, the number of milliseconds to wait after
     * user interaction before hiding the system UI.
     */
    private static final int AUTO_HIDE_DELAY_MILLIS = 3000;

    /**
     * Some older devices needs a small delay between UI widget updates
     * and a change of the status and navigation bar.
     */
    private static final int UI_ANIMATION_DELAY = 300;
    private final Handler mHideHandler = new Handler();
    private int mFormat = 0;
    private Size mCaptureSize = null;
    private Size mPreviewSize = null;
    private CaptureRequest.Builder mPreviewRequest= null;
    private int mImageCount =0;

    private final Runnable mHidePart2Runnable = new Runnable() {
        @SuppressLint("InlinedApi")
        @Override
        public void run() {
            // Delayed removal of status and navigation bar

            // Note that some of these constants are new as of API 16 (Jelly Bean)
            // and API 19 (KitKat). It is safe to use them, as they are inlined
            // at compile-time and do nothing on earlier devices.
            mViewFinder.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    };
    private View mControlsView;
    private final Runnable mShowPart2Runnable = new Runnable() {
        @Override
        public void run() {
            // Delayed display of UI elements
            ActionBar actionBar = getSupportActionBar();
            if (actionBar != null) {
                actionBar.show();
            }
            mControlsView.setVisibility(View.VISIBLE);
        }
    };
    private boolean mVisible;
    private final Runnable mHideRunnable = new Runnable() {
        @Override
        public void run() {
            hide();
        }
    };
    /**
     * Touch listener to use for in-layout UI controls to delay hiding the
     * system UI. This is to prevent the jarring behavior of controls going away
     * while interacting with activity UI.
     */
    private final View.OnTouchListener mDelayHideTouchListener = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View view, MotionEvent motionEvent) {
            switch (motionEvent.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    if (AUTO_HIDE) {
                        delayedHide(AUTO_HIDE_DELAY_MILLIS);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    view.performClick();
                    break;
                default:
                    break;
            }
            return false;
        }
    };
    private ActivityFullscreenBinding binding;
    private AutoFitTextureView mViewFinder ;
    private String mCamID;
    private CameraDevice mCamera;
    private Handler mBGHandler;
    private HandlerThread mBGThread;
    private CameraCaptureSession mSession;


    private CameraCaptureSession.CaptureCallback mCaptureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            mExposureTimesISO=(double)(result.get(TotalCaptureResult.SENSOR_SENSITIVITY)*result.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME));;
            Log.d(LOG_TAG,"ISO:"+result.get(TotalCaptureResult.SENSOR_SENSITIVITY)+
                    " SS:"+result.get(TotalCaptureResult.SENSOR_EXPOSURE_TIME));
            super.onCaptureCompleted(session, request, result);
        }
    };
    double mExposureTimesISO=0;

    private ImageReader mImageWriter;


    void startBGThread() {
        mBGThread = new HandlerThread("imageSaving");
        mBGThread.start();
        mBGHandler = new Handler(mBGThread.getLooper());
    }

    void stopBGThread() {
        if (mBGThread != null) {
            mBGThread.quitSafely();
            try {
                mBGThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            mBGThread = null;
            mBGHandler = null;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ContentResolver resolver = getApplicationContext().getContentResolver();
//        Cursor cursor= resolver.query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),new String[]{MediaStore.Images.Media.RELATIVE_PATH},null,null,null,null);//MediaStore.Images.Media.DATE_ADDED+" DESC",null);
        Cursor cursor= resolver.query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),new String[]{MediaStore.Images.Media.DISPLAY_NAME},MediaStore.Images.Media.RELATIVE_PATH + " = ?",new String[]{FOLDER_NAME},MediaStore.Images.Media.DATE_TAKEN+" DESC",null);
        if(cursor!=null)
        {
            if(cursor.moveToFirst()) {
                Log.d(LOG_TAG, "LAST IMAGE" + cursor.getString(0));
                String[] splits=cursor.getString(0).split("_");
                if(splits.length==3)
                {
                    int lastImageSet=Integer.valueOf(splits[1]);
                    mImageCount=(lastImageSet+1)*3;
                }
            }else
            {
                Log.d(LOG_TAG,"EMPTY STORE:"+FOLDER_NAME);
            }
            cursor.close();
        }
        Log.d(LOG_TAG,"IMAGE COUNT:"+mImageCount);




        binding = ActivityFullscreenBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        mViewFinder= binding.viewfinder;
        mVisible = true;
        mControlsView = binding.fullscreenContentControls;


        // Set up the user interaction to manually show or hide the system UI.
/*        mContentView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggle();
            }
        });*/

        // Upon interacting with UI controls, delay any scheduled hide()
        // operations to prevent the jarring behavior of controls going away
        // while interacting with the UI.
        binding.photoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                takePhotos();
            }
        });
    }


    void takePhotos()
    {
        try {
            double exposureAtISO100=mExposureTimesISO/100.0;
            double exposureAtISO1600=mExposureTimesISO/1600.0;
            LinkedList<CaptureRequest> requests=new LinkedList<CaptureRequest>();
            CaptureRequest.Builder build=mCamera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            build.addTarget(mImageWriter.getSurface());

            build.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_OFF);

            build.set(CaptureRequest.CONTROL_AF_MODE,CaptureRequest.CONTROL_AF_MODE_AUTO);
            build.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_START);
            build.set(CaptureRequest.SENSOR_SENSITIVITY,100);
            build.set(CaptureRequest.SENSOR_EXPOSURE_TIME,(long)exposureAtISO100);
            requests.add(build.build());

            build.set(CaptureRequest.CONTROL_AF_TRIGGER,CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            build.set(CaptureRequest.SENSOR_SENSITIVITY,1600);
            build.set(CaptureRequest.SENSOR_EXPOSURE_TIME,(long)exposureAtISO1600);
            requests.add(build.build());

            build.set(CaptureRequest.SENSOR_SENSITIVITY,100);
            build.set(CaptureRequest.SENSOR_EXPOSURE_TIME,(long)exposureAtISO100);
            requests.add(build.build());

            mSession.captureBurst(requests,mCaptureCallback,mBGHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults)
    {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        initCamera();
        startBGThread();
    }

    void initCameraAfterTexture() {
        CameraManager cam = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        String found_cam_id = null;
        try {
            for (String id : cam.getCameraIdList()) {

                CameraCharacteristics characteristics
                        = cam.getCameraCharacteristics(id);
                int facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
//                if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                    found_cam_id = id;
                    break;
                }
            }

        } catch (CameraAccessException e) {
            Toast.makeText(this, "Couldn't access camera", Toast.LENGTH_LONG).show();
        }
        if (found_cam_id != null) {
            Log.d(LOG_TAG, "Got cam:" + found_cam_id);
            CameraCharacteristics characteristics
                    = null;
            try {
                characteristics = cam.getCameraCharacteristics(found_cam_id);
                // get the supported stream input types
                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                chooseResolution(map);
                choosePreviewResolution(characteristics, map);
                mCamID = found_cam_id;

            } catch (CameraAccessException e) {
                Toast.makeText(this, "Couldn't access camera second call", Toast.LENGTH_LONG).show();
            }
        }
        startCamera();
    }

    @Override
    protected void onResume() {


        super.onResume();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            int[] results;
            ActivityCompat.requestPermissions(this,new String[] {Manifest.permission.CAMERA},1);
            return;
        }
        // if we already have the permission call through to success anyway
        onRequestPermissionsResult(-1,new String[]{Manifest.permission.CAMERA},new int[]{1});



    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBGThread();
        super.onPause();
    }

    private void closeCamera()
    {
        if(mSession!=null)
        {
            mSession.close();
            mSession=null;
        }
        if(mCamera!=null)
        {
            mCamera.close();
            mCamera=null;
        }
        if(mImageWriter!=null)
        {
            mImageWriter.close();
            mImageWriter=null;
        }
    }

    void initCamera()
    {
        Log.d(LOG_TAG,"Init camera");
        if(mViewFinder.getSurfaceTexture()!=null)
        {
            initCameraAfterTexture();
        }else {
            mViewFinder.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {
                    initCameraAfterTexture();
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surfaceTexture, int i, int i1) {

                }

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surfaceTexture) {
                    return false;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surfaceTexture) {

                }
            });
        }
    }
    @SuppressLint("MissingPermission")
    void startCamera() {
        Log.d(LOG_TAG,"START CAMERA");
        CameraManager cam = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            cam.openCamera(mCamID, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(@NonNull CameraDevice cameraDevice) {
                    mCamera = cameraDevice;
                    Log.d(LOG_TAG,"Opened camera");
                    createCameraSession();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice cameraDevice) {
                    mCamera = null;
                }

                @Override
                public void onError(@NonNull CameraDevice cameraDevice, int i) {

                }
            }, mBGHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    void createCameraSession()
    {
        mImageWriter= ImageReader.newInstance(mCaptureSize.getWidth(),mCaptureSize.getHeight(),ImageFormat.JPEG,10);
        mImageWriter.setOnImageAvailableListener(
                new ImageReader.OnImageAvailableListener() {
                    @Override
                    public void onImageAvailable(ImageReader imageReader) {
                        saveImage(imageReader.acquireNextImage());
                    }
                }
                ,mBGHandler);
        SurfaceTexture texture = mViewFinder.getSurfaceTexture();
        texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface surface = new Surface(texture);
        try {
            mPreviewRequest = mCamera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        mPreviewRequest.addTarget(surface);
        StateCallback sc=new StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                if(mCamera!=null)
                {
                    mSession=cameraCaptureSession;
                    // start showing the camera preview
                    mPreviewRequest.set(CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                    CaptureRequest cr=mPreviewRequest.build();
                    try {
                        cameraCaptureSession.setRepeatingRequest(cr,
                                mCaptureCallback, mBGHandler);
                    } catch (CameraAccessException e) {
                        Log.e(LOG_TAG,"Couldn't start preview session");
                    }
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {

            }
        };
        try {
            mCamera.createCaptureSession(Arrays.asList(surface,mImageWriter.getSurface()), sc,null);
        } catch (CameraAccessException e) {
            Log.e(LOG_TAG,"Couldn't create capture session");
        }


    }

    private void saveImage(Image img) {
        int imageSetID = mImageCount % 3;
        int imageSetNum = mImageCount / 3;
        File saveName = new File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), String.format("blurset_%04d_%04d.jpg", imageSetNum, imageSetID));
        mImageCount += 1;
        // image is a jpeg always so we can then just chuck it straight out
        // to a file
        Log.d(LOG_TAG, "Save IMAGE");
        ByteBuffer buffer = img.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        Toast.makeText(this, "Write:" + saveName.getName(), Toast.LENGTH_SHORT).show();

        ContentResolver resolver = getApplicationContext().getContentResolver();
        ContentValues imageDetails = new ContentValues();
        imageDetails.put(MediaStore.Images.Media.DISPLAY_NAME, saveName.getName());
        imageDetails.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        imageDetails.put(MediaStore.Images.Media.RELATIVE_PATH, FOLDER_NAME);
        imageDetails.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), imageDetails);
        if (uri != null) {
            try {
                ParcelFileDescriptor f=resolver.openFileDescriptor(uri,"w",null);
                FileOutputStream stream=new FileOutputStream(f.getFileDescriptor());
                stream.write(bytes);
                stream.close();
                f.close();
                imageDetails.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, imageDetails,null,null);
                Log.d(LOG_TAG,"Written file okay:"+uri.toString());
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    void chooseResolution(StreamConfigurationMap map)
    {
        // format = either JPG, or YUV
        int[] inFormats= {ImageFormat.JPEG};

        // get the biggest sizes of each
        // if there is a zero delay jpg option then use that,
        // otherwise get the biggest YUV option
        Size largestSize=null;
        int largestFormat=0;
        int largestArea=0;
        long largestDelay=0;
        for( int format : inFormats)
        {
            if(map.isOutputSupportedFor(format))
            {
                AtomicReference<Long> delay=new AtomicReference<Long>(0l);
                Size formatSize=getLargestResolution(map,format,delay);
                int area=formatSize.getWidth()*formatSize.getHeight();
                if(area>largestArea || (area==largestArea && delay.get()<largestDelay))
                {
                    largestArea=area;
                    largestSize=formatSize;
                    largestDelay=delay.get();
                    largestFormat=format;
                }
            }
        }
        mFormat=largestFormat;
        mCaptureSize=largestSize;
        Log.d(LOG_TAG,"Output format:"+mFormat+"("+mCaptureSize.getWidth()+"*"+mCaptureSize.getHeight()+")");
    }

    void choosePreviewResolution(CameraCharacteristics characteristics,StreamConfigurationMap map)
    {
        Size[] sizes=map.getOutputSizes(SurfaceTexture.class);
        int displayW=mViewFinder.getWidth();
        int displayH=mViewFinder.getHeight();

        int camOrientation=characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

        int displayOrientation=getWindowManager().getDefaultDisplay().getRotation();
        boolean displayPortrait=displayOrientation== Surface.ROTATION_0 || displayOrientation==Surface.ROTATION_180;
        boolean camPortrait= camOrientation==0 || camOrientation==180;

        if(displayPortrait!=camPortrait)
        {
            displayW=mViewFinder.getHeight();
            displayH=mViewFinder.getWidth();
        }

        boolean previousBiggerThanScreen=false;
        for(Size s:sizes)
        {
            boolean biggerThanScreen=(s.getWidth()>=displayW && s.getHeight()>=displayH);
            // this size is bigger than the screen, choose it if it is less than the previous size
            // or if the previous size is smaller than screen
            if(mPreviewSize!=null)
            {
                boolean biggerThanPrevious=(s.getWidth()>mPreviewSize.getWidth() && s.getHeight()>mPreviewSize.getHeight());
                if(biggerThanScreen)
                {
                    // choose bigger than screen size
                    if(previousBiggerThanScreen==false)
                    {
                        mPreviewSize=s;
                    }else if(!biggerThanPrevious)
                    {
                        // only choose a smaller one
                        mPreviewSize=s;
                    }
                }else
                {
                    // smaller than screen - choose if bigger than previous
                    if(biggerThanPrevious)
                    {
                        mPreviewSize=s;
                    }
                }
            }else
            {
                mPreviewSize=s;
            }
            previousBiggerThanScreen=biggerThanScreen;
        }

        int orientation = getResources().getConfiguration().orientation;
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            mViewFinder.setAspectRatio(
                    mPreviewSize.getWidth(), mPreviewSize.getHeight());
        } else {
            mViewFinder.setAspectRatio(
                    mPreviewSize.getHeight(), mPreviewSize.getWidth());
        }
        Log.d(LOG_TAG,"Preview size:"+"("+mPreviewSize.getWidth()+"*"+mPreviewSize.getHeight()+")");

    }

    Size getLargestResolution(StreamConfigurationMap map, int format, AtomicReference<Long> processingDelay)
    {
        int maxArea=-1;
        Size largestSize=null;
        Size[] sizes=map.getOutputSizes(format);
        for(Size s : sizes)
        {
            int area=s.getWidth()*s.getHeight();
            if(area>maxArea)
            {
                largestSize=s;
                maxArea=area;
                processingDelay.set(map.getOutputStallDuration(format,s));
            }
        }
        return largestSize;
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState) {
        super.onPostCreate(savedInstanceState);

/*        // Trigger the initial hide() shortly after the activity has been
        // created, to briefly hint to the user that UI controls
        // are available.
        delayedHide(100);*/
    }

    private void toggle() {
        if (mVisible) {
            hide();
        } else {
            show();
        }
    }

    private void hide() {
        // Hide UI first
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
        mControlsView.setVisibility(View.GONE);
        mVisible = false;

        // Schedule a runnable to remove the status and navigation bar after a delay
        mHideHandler.removeCallbacks(mShowPart2Runnable);
        mHideHandler.postDelayed(mHidePart2Runnable, UI_ANIMATION_DELAY);
    }

    private void show() {
        // Show the system bar
        mViewFinder.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mVisible = true;

        // Schedule a runnable to display UI elements after a delay
        mHideHandler.removeCallbacks(mHidePart2Runnable);
        mHideHandler.postDelayed(mShowPart2Runnable, UI_ANIMATION_DELAY);
    }

    /**
     * Schedules a call to hide() in delay milliseconds, canceling any
     * previously scheduled calls.
     */
    private void delayedHide(int delayMillis) {
        mHideHandler.removeCallbacks(mHideRunnable);
        mHideHandler.postDelayed(mHideRunnable, delayMillis);
    }
}