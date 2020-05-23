package org.apache.cordova.mediacapture;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraDevice.StateCallback;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.util.Log;
import android.util.Size;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

enum Flag {
    DOWN_MOVING, DOWN, MOVING, UNSET
}

public class CamService extends Service {
    public static final String TAG = "CamService";

    public static final String ACTION_START = "com.example.camera2videoservice.action.START";
    public static final String ACTION_START_WITH_PREVIEW = "com.example.camera2videoservice.action.START_WITH_PREVIEW";
    public static final String ACTION_STOPPED = "com.example.camera2videoservice.action.STOPPED";
    public static final String ACTION_HIDE_PREVIEW = "com.example.camera2videoservice.action.ACTION_HIDE_PREVIEW";
    public static final String ACTION_SHOW_PREVIEW = "com.example.camera2videoservice.action.ACTION_SHOW_PREVIEW";
    public static final String ACTION_SWITCH_CAMERA = "com.example.camera2videoservice.action.ACTION_SWITCH_CAMERA";

    public static final int ONGOING_NOTIFICATION_ID = 6660;
    public static final String CHANNEL_ID = "cam_service_channel_id";
    public static final String CHANNEL_NAME = "cam_service_channel_name";
    private final int TARGET_HEIGHT = 1280;
    private final int TARGET_WIDTH = 720;
    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        public void onCaptureProgressed(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                @NonNull CaptureResult partialResult) {
        }

        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request,
                @NonNull TotalCaptureResult result) {
        }
    };
    private WindowManager wm;
    private TextureView textureView;
    private CameraManager cameraManager;
    private Size previewSize;
    private CameraDevice cameraDevice;
    private CaptureRequest captureRequest;
    private CameraCaptureSession captureSession;
    private boolean shouldShowPreview = true;
    private MediaRecorder mediaRecorder;
    private String nextVideoAbsolutePath;
    private boolean isFirstTime = true;
    private int bitRate = 5000000;
    private int frameRate = 25;
    private int duration = 1000 * 60 * 30;
    private PendingIntent callerContentIntent;
    private String appName;
    private String taskName;
    private int availableCamNum;
    private int selectCamIndx = 0;
    private final StateCallback stateCallback = new StateCallback() {
        public void onOpened(@NonNull CameraDevice currentCameraDevice) {
            cameraDevice = currentCameraDevice;
            startRecording();
        }

        public void onDisconnected(CameraDevice currentCameraDevice) {
            currentCameraDevice.close();
            cameraDevice = null;
        }

        public void onError(CameraDevice currentCameraDevice, int error) {
            currentCameraDevice.close();
            cameraDevice = null;
        }
    };
    private float oneDp = 1;
    private TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            initCam(TARGET_HEIGHT, TARGET_WIDTH);
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
        }

    };

    public CamService() {
    }

    @Override
    public IBinder onBind(Intent intent) {
        // TODO: Return the communication channel to the service.
        return null;
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        Bundle options = intent.getBundleExtra("options");
        callerContentIntent = intent.getParcelableExtra("_contentIntent");
        if (options != null) {
            appName = options.getString(PendingRequests.Request.APP_NAME_KEY);
            taskName = options.getString(PendingRequests.Request.TASK_NAME_KEY);
            bitRate = options.getInt(PendingRequests.Request.BPS_KEY);
            frameRate = options.getInt(PendingRequests.Request.FPS_KEY);
            duration = options.getInt(PendingRequests.Request.DURATION_KEY);
        }

        if (isFirstTime) {
            startForeground();
            if (Objects.equals(intent.getAction(), ACTION_START)) {
                start();
            } else if (Objects.equals(intent.getAction(), ACTION_START_WITH_PREVIEW)) {
                startWithPreview();
            }
            isFirstTime = false;
        }

        if (Objects.equals(intent.getAction(), ACTION_HIDE_PREVIEW)) {
            hideTexture();
        } else if (Objects.equals(intent.getAction(), ACTION_SHOW_PREVIEW)) {
            showTexture();
        } else if (Objects.equals(intent.getAction(), ACTION_SWITCH_CAMERA)) {
            switchCamera();
        }
        return super.onStartCommand(intent, flags, startId);
    }

    public void onCreate() {
        super.onCreate();
        oneDp = getResources().getDisplayMetrics().density;
        // scheduleJobs();
    }

    public void onDestroy() {
        super.onDestroy();
        stopCamera();
        if (textureView != null) {
            wm.removeView(textureView);
        }

        this.sendBroadcast(new Intent(ACTION_STOPPED));
    }

    private void switchCamera() {
        if (this.availableCamNum > 1) {
            this.selectCamIndx = (this.selectCamIndx + 1) % this.availableCamNum;
            stopCamera();
            if (this.shouldShowPreview) {
                this.startWithPreview();
            } else {
                this.start();
            }
        }
    }

    private void start() {
        shouldShowPreview = false;
        initCam(TARGET_HEIGHT, TARGET_WIDTH);
    }

    private void startWithPreview() {
        shouldShowPreview = true;
        initOverlay();

        if (textureView.isAvailable()) {
            initCam(TARGET_HEIGHT, TARGET_WIDTH);
        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void initOverlay() {
        this.textureView = new TextureView(this);
        int type = Build.VERSION.SDK_INT < Build.VERSION_CODES.O ? WindowManager.LayoutParams.TYPE_PHONE
                : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

        final WindowManager.LayoutParams params = new WindowManager.LayoutParams((int) (90 * oneDp),
                (int) (120 * oneDp), 0, 0, type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Objects.requireNonNull(wm).addView(textureView, params);

        textureView.setOnTouchListener((new View.OnTouchListener() {
            private int lastX;
            private int lastY;
            private int nowX;
            private int nowY;
            private int tranX;
            private int tranY;
            private Flag flag = Flag.UNSET;
            private boolean isLarge = false;
            private long lastDownTime = 0;

            private void toggleZoom() {
                if (this.isLarge) {
                    this.zoomOut();
                } else {
                    this.zoomIn();
                }
                this.isLarge = !this.isLarge;
            }

            private void zoomIn() {
                params.width = (int) (360 * oneDp);
                params.height = (int) (480 * oneDp);
                wm.updateViewLayout(textureView, params);
            }

            private void zoomOut() {
                params.width = (int) (90 * oneDp);
                params.height = (int) (120 * oneDp);
                wm.updateViewLayout(textureView, params);
            }

            public boolean onTouch(View v, MotionEvent event) {
                boolean ret = false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        this.flag = Flag.DOWN;
                        this.lastX = (int) event.getRawX();
                        this.lastY = (int) event.getRawY();
                        ret = true;
                        this.lastDownTime = System.currentTimeMillis();
                        break;
                    case MotionEvent.ACTION_MOVE:
                        if (this.flag == Flag.DOWN || this.flag == Flag.DOWN_MOVING) {
                            this.flag = Flag.DOWN_MOVING;
                            this.nowX = (int) event.getRawX();
                            this.nowY = (int) event.getRawY();
                            this.tranX = this.nowX - this.lastX;
                            this.tranY = this.nowY - this.lastY;
                            params.x += this.tranX;
                            params.y += this.tranY;
                            wm.updateViewLayout(textureView, params);
                            this.lastX = this.nowX;
                            this.lastY = this.nowY;
                        } else {
                            this.flag = Flag.MOVING;
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                        if (this.flag == Flag.DOWN || this.flag == Flag.DOWN_MOVING) {
                            if (System.currentTimeMillis() - this.lastDownTime < 200) {
                                // click event should < 200ms
                                this.toggleZoom();
                            }
                        }
                        break;
                }
                return ret;
            }
        }));
    }

    @SuppressLint("MissingPermission")
    private void initCam(int width, int height) {
        try {
            cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            String[] availableCamList = Objects.requireNonNull(cameraManager).getCameraIdList();
            this.availableCamNum = availableCamList.length;
            String selectCamId = availableCamList[selectCamIndx];
            previewSize = chooseSupportedSize(selectCamId, width, height);
            mediaRecorder = new MediaRecorder();
            cameraManager.openCamera(selectCamId, stateCallback, null);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private Size chooseSupportedSize(String camId, int textureViewWidth, int textureViewHeight) {
        // CameraCharacteristics characteristics =
        // cameraManager.getCameraCharacteristics(camId);
        // StreamConfigurationMap map =
        // characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        // Size[] supportedSizes = map != null ?
        // map.getOutputSizes(SurfaceTexture.class) : null;
        //
        // final int texViewArea = textureViewWidth * textureViewHeight;
        // final float texViewAspect = (float)textureViewWidth /
        // (float)textureViewHeight;

        return new Size(TARGET_HEIGHT, TARGET_WIDTH);
    }

    private void startForeground() {
        // PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, new
        // Intent(this, callerContentIntent), 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_NONE);
            channel.setLightColor(Color.BLUE);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            Objects.requireNonNull(nm).createNotificationChannel(channel);
        }

        @SuppressLint("PrivateResource")
        Notification notification = (new NotificationCompat.Builder(this, CHANNEL_ID))
                .setContentTitle(appName + "title").setContentText(appName + "text")
                // .setSmallIcon(callerR.drawable.notification_template_icon_bg)
                .setContentIntent(callerContentIntent).setTicker(appName + "ticker").build();
        startForeground(ONGOING_NOTIFICATION_ID, notification);
    }

    private void startRecording() {
        try {
            setUpMediaRecorder();
            Surface recorderSurface = mediaRecorder.getSurface();
            ArrayList<Surface> targetSurfaces = new ArrayList<Surface>();

            if (shouldShowPreview) {
                SurfaceTexture texture = textureView.getSurfaceTexture();

                texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                Surface previewSurface = new Surface(texture);
                targetSurfaces.add(previewSurface);
            }

            targetSurfaces.add(recorderSurface);

            final CaptureRequest.Builder requestBuilder = cameraDevice
                    .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            if (shouldShowPreview) {
                SurfaceTexture texture = textureView.getSurfaceTexture();
                texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                Surface previewSurface = new Surface(texture);
                requestBuilder.addTarget(previewSurface);
            }

            requestBuilder.addTarget(recorderSurface);
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
            cameraDevice.createCaptureSession(targetSurfaces, new CameraCaptureSession.StateCallback() {
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    if (cameraDevice != null) {
                        captureSession = cameraCaptureSession;

                        try {
                            captureRequest = requestBuilder.build();
                            captureSession.setRepeatingRequest(captureRequest, captureCallback, null);
                        } catch (CameraAccessException e) {
                            Log.e(TAG, "createCaptureSession", e);
                        }
                        mediaRecorder.start();
                    }
                }

                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Log.e(TAG, "createCaptureSession()");
                }
            }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "createCaptureSession", e);
        }

    }

    private void stopRecordingVideo() {
        captureSession.close();
        captureSession = null;
        mediaRecorder.stop();
        mediaRecorder.reset();

        Log.d("service", "video saved: " + nextVideoAbsolutePath);
        this.nextVideoAbsolutePath = null;
    }

    private void restartRecording() {
        this.stopRecordingVideo();
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        this.mediaRecorder.release();
        this.mediaRecorder = null;
        this.mediaRecorder = new MediaRecorder();
        this.startRecording();
        Log.d(TAG, "recording restarted");
    }

    private void scheduleJobs() {
        // final LocalDateTime endAt = this.stringToLocalDateTime("18:20");
        //
        // new Timer().schedule(new TimerTask() {
        // public void run() {
        // // 防止过时的时间触发
        // if (endAt.compareTo(LocalDateTime.now().plusSeconds(-1)) > 0) {
        // stopSelf();
        // // showToast("alarm!");
        // Log.d("test", "timer is ringing");
        // }
        // }
        // }, Date.from(endAt.atZone(ZoneId.systemDefault()).toInstant()));
    }

    private void stopCamera() {
        try {
            stopRecordingVideo();

            captureSession.close();
            captureSession = null;

            cameraDevice.close();
            cameraDevice = null;

            mediaRecorder.release();
            mediaRecorder = null;
        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private void setUpMediaRecorder() {
        if (nextVideoAbsolutePath == null || nextVideoAbsolutePath.length() == 0) {
            nextVideoAbsolutePath = getVideoFilePath(this);
        }

        if (mediaRecorder != null) {
            mediaRecorder.setOrientationHint(0);
            // mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            // mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setOutputFile(nextVideoAbsolutePath);
            mediaRecorder.setVideoEncodingBitRate(bitRate);
            mediaRecorder.setVideoFrameRate(frameRate);
            mediaRecorder.setVideoSize(previewSize.getWidth(), previewSize.getHeight());
            mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            int duration_fix = (int) (duration - System.currentTimeMillis() % (duration));
            mediaRecorder.setMaxDuration(duration_fix); // 单位ms
            try {
                mediaRecorder.prepare();
            } catch (IOException e) {
                e.printStackTrace();
            }
            mediaRecorder.setOnInfoListener(new MediaRecorder.OnInfoListener() {
                @Override
                public void onInfo(MediaRecorder recorder, int what, int extra) {
                    if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) {
                        restartRecording();
                    }
                }
            });
        }

    }

    private String getVideoFilePath(Context context) {
        SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd");
        SimpleDateFormat timeFormatter = new SimpleDateFormat("HH_mm_ss");
        Date now = new Date(System.currentTimeMillis());

        String filename = timeFormatter.format(now) + ".mp4";
        File dir = context != null
                ? new File(Environment.getExternalStorageDirectory(),
                        "/YXD/" + (taskName != null ? taskName + "/" : "") + dateFormatter.format(now))
                : null;

        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir == null ? filename : dir.getAbsolutePath() + '/' + filename;
    }

    private void hideTexture() {
        if (textureView != null) {
            textureView.setVisibility(View.GONE);
        }
    }

    private void showTexture() {
        if (textureView != null) {
            textureView.setVisibility(View.VISIBLE);
        }
    }
}