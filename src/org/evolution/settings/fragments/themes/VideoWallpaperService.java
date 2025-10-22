/*
 * Copyright (C) 2024-2025 Lunaris AOSP
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.evolution.settings.fragments.themes;

import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.util.Log;
import android.view.SurfaceHolder;

import org.evolution.settings.utils.MediaUtils;

import java.io.File;
import java.io.IOException;

public class VideoWallpaperService extends WallpaperService {
    private static final String TAG = "VideoWallpaperService";
    private static final String PREFS_NAME = "video_wallpaper_prefs";

    @Override
    public Engine onCreateEngine() {
        return new VideoEngine();
    }

    private class VideoEngine extends Engine {
        private MediaPlayer mediaPlayer;
        private boolean isVisible = false;
        private boolean isPrepared = false;
        private SharedPreferences prefs;
        private SharedPreferences.OnSharedPreferenceChangeListener prefsListener;
        private Handler handler;
        private String currentVideoPath;
        
        private float currentPlaybackSpeed = 1.0f;
        private boolean pauseOnLock = true;

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            
            handler = new Handler(Looper.getMainLooper());
            prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            
            loadSettings();
            
            prefsListener = (sharedPreferences, key) -> {
                Log.d(TAG, "Preference changed: " + key);
                
                if ("current_wallpaper_path".equals(key)) {
                    String newPath = sharedPreferences.getString(key, null);
                    if (newPath != null && !newPath.equals(currentVideoPath)) {
                        Log.d(TAG, "New video detected, reloading...");
                        currentVideoPath = newPath;
                        if (handler != null) {
                            handler.post(() -> {
                                releaseMediaPlayer();
                                createMediaPlayer(getSurfaceHolder());
                            });
                        }
                    }
                } else {
                    loadSettings();
                    if (mediaPlayer != null && isPrepared) {
                        applyPlaybackSpeed();
                    }
                }
            };
            prefs.registerOnSharedPreferenceChangeListener(prefsListener);
            
            setTouchEventsEnabled(false);
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            isVisible = visible;
            
            if (visible) {
                if (mediaPlayer != null && isPrepared && !mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                    Log.d(TAG, "MediaPlayer started (visible)");
                }
            } else {
                if (pauseOnLock && mediaPlayer != null && mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                    Log.d(TAG, "MediaPlayer paused (not visible)");
                }
            }
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            createMediaPlayer(holder);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            super.onSurfaceDestroyed(holder);
            releaseMediaPlayer();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            
            if (prefsListener != null && prefs != null) {
                prefs.unregisterOnSharedPreferenceChangeListener(prefsListener);
                prefsListener = null;
            }
            
            if (handler != null) {
                handler.removeCallbacksAndMessages(null);
                handler = null;
            }
            
            releaseMediaPlayer();
            prefs = null;
            
            Log.d(TAG, "Engine destroyed, all resources cleaned");
        }

        private void loadSettings() {
            if (prefs == null) return;
            
            pauseOnLock = prefs.getBoolean("pause_on_lock", true);
            currentVideoPath = prefs.getString("current_wallpaper_path", null);
            
            String speedStr = prefs.getString("playback_speed", "1.0");
            try {
                currentPlaybackSpeed = Float.parseFloat(speedStr);
                currentPlaybackSpeed = Math.max(0.25f, Math.min(2.0f, currentPlaybackSpeed));
            } catch (NumberFormatException e) {
                currentPlaybackSpeed = 1.0f;
            }
            
            Log.d(TAG, "Settings loaded - Speed: " + currentPlaybackSpeed + "x, PauseOnLock: " + pauseOnLock);
        }

        private void createMediaPlayer(SurfaceHolder holder) {
            try {
                File wallpaperFile = MediaUtils.getCurrentWallpaperFile(VideoWallpaperService.this);
                
                if (wallpaperFile == null) {
                    Log.e(TAG, "Wallpaper file is null");
                    drawErrorMessage(holder, "No wallpaper configured");
                    return;
                }
                
                if (!wallpaperFile.exists()) {
                    Log.e(TAG, "Wallpaper file does not exist: " + wallpaperFile.getAbsolutePath());
                    drawErrorMessage(holder, "Wallpaper file not found");
                    return;
                }
                
                if (!wallpaperFile.canRead()) {
                    Log.e(TAG, "Cannot read wallpaper file");
                    drawErrorMessage(holder, "Cannot read wallpaper");
                    return;
                }

                Log.d(TAG, "Loading wallpaper: " + wallpaperFile.getAbsolutePath());
                currentVideoPath = wallpaperFile.getAbsolutePath();

                mediaPlayer = new MediaPlayer();
                mediaPlayer.setSurface(holder.getSurface());
                mediaPlayer.setDataSource(wallpaperFile.getAbsolutePath());
                mediaPlayer.setLooping(true);
                mediaPlayer.setVolume(0f, 0f);

                mediaPlayer.setOnPreparedListener(mp -> {
                    isPrepared = true;
                    Log.d(TAG, "MediaPlayer prepared");
                    
                    applyPlaybackSpeed();
                    
                    if (isVisible) {
                        mp.start();
                        Log.d(TAG, "MediaPlayer started");
                    }
                });

                mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                    Log.e(TAG, "MediaPlayer error: what=" + what + ", extra=" + extra);
                    String errorMsg = getErrorMessage(what);
                    releaseMediaPlayer();
                    drawErrorMessage(holder, errorMsg);
                    return true;
                });

                mediaPlayer.setOnInfoListener((mp, what, extra) -> {
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                        Log.d(TAG, "Video rendering started");
                    }
                    return false;
                });

                mediaPlayer.setOnCompletionListener(mp -> {
                    Log.w(TAG, "Video completed unexpectedly");
                    if (isVisible) {
                        mp.start();
                    }
                });

                mediaPlayer.prepareAsync();

            } catch (IOException e) {
                Log.e(TAG, "Error creating MediaPlayer: " + e.getMessage(), e);
                releaseMediaPlayer();
                drawErrorMessage(holder, "Failed to load video");
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error: " + e.getMessage(), e);
                releaseMediaPlayer();
                drawErrorMessage(holder, "Unexpected error");
            }
        }

        private void applyPlaybackSpeed() {
            if (mediaPlayer == null || !isPrepared) return;
            
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    android.media.PlaybackParams params = mediaPlayer.getPlaybackParams();
                    params.setSpeed(currentPlaybackSpeed);
                    mediaPlayer.setPlaybackParams(params);
                    Log.d(TAG, "Playback speed applied: " + currentPlaybackSpeed + "x");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error setting playback speed: " + e.getMessage());
            }
        }

        private String getErrorMessage(int errorCode) {
            switch (errorCode) {
                case MediaPlayer.MEDIA_ERROR_SERVER_DIED:
                    return "Media server died";
                case MediaPlayer.MEDIA_ERROR_UNKNOWN:
                    return "Unknown error";
                default:
                    return "Playback error (" + errorCode + ")";
            }
        }

        private void drawErrorMessage(SurfaceHolder holder, String message) {
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas != null) {
                    canvas.drawColor(0xFF1C1B1F);
                    
                    Paint paint = new Paint();
                    paint.setAntiAlias(true);
                    
                    paint.setColor(0xFFB3261E);
                    paint.setTextSize(48);
                    paint.setTextAlign(Paint.Align.CENTER);
                    canvas.drawText("⚠", canvas.getWidth() / 2f, canvas.getHeight() / 2f - 100, paint);
                    
                    paint.setColor(0xFFE6E1E5);
                    paint.setTextSize(28);
                    String[] lines = message.split("\n");
                    float y = canvas.getHeight() / 2f;
                    
                    for (String line : lines) {
                        canvas.drawText(line, canvas.getWidth() / 2f, y, paint);
                        y += 36;
                    }
                    
                    paint.setTextSize(18);
                    paint.setColor(0xFF938F99);
                    canvas.drawText("Open Settings → Themes → Video Wallpaper", 
                                  canvas.getWidth() / 2f, 
                                  canvas.getHeight() - 100f, 
                                  paint);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error drawing error message: " + e.getMessage());
            } finally {
                if (canvas != null) {
                    try {
                        holder.unlockCanvasAndPost(canvas);
                    } catch (Exception e) {
                        Log.e(TAG, "Error unlocking canvas: " + e.getMessage());
                    }
                }
            }
        }

        private void releaseMediaPlayer() {
            if (mediaPlayer != null) {
                try {
                    if (mediaPlayer.isPlaying()) {
                        mediaPlayer.stop();
                    }
                    mediaPlayer.reset();
                    mediaPlayer.release();
                } catch (Exception e) {
                    Log.e(TAG, "Error releasing MediaPlayer: " + e.getMessage());
                } finally {
                    mediaPlayer = null;
                    isPrepared = false;
                    Log.d(TAG, "MediaPlayer released");
                }
            }
        }
    }
}