package com.chatscopex.app.util;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Records a voice note as AAC in an MP4 container (audio/mp4). Needs the RECORD_AUDIO permission. */
public final class VoiceRecorder {
    private static final long MIN_MS = 800;

    private MediaRecorder recorder;
    private File file;
    private long startedAt;

    public boolean isRecording() { return recorder != null; }

    public void start(Context c) throws IOException {
        file = new File(c.getCacheDir(), "voice_note.m4a");
        MediaRecorder r = new MediaRecorder();
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC);
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioEncodingBitRate(64000);
            r.setAudioSamplingRate(44100);
            r.setMaxDuration(5 * 60 * 1000);
            r.setOutputFile(file.getAbsolutePath());
            r.prepare();
            r.start();
        } catch (RuntimeException | IOException e) {
            r.release();
            throw new IOException(e);
        }
        recorder = r;
        startedAt = SystemClock.elapsedRealtime();
    }

    /** Stops and returns the recording, or null when it was too short or failed. */
    public byte[] stop() {
        if (recorder == null) return null;
        MediaRecorder r = recorder;
        recorder = null;
        boolean longEnough = SystemClock.elapsedRealtime() - startedAt >= MIN_MS;
        try {
            r.stop();
        } catch (RuntimeException e) {
            longEnough = false;   // stop() throws when nothing was captured
        } finally {
            r.release();
        }
        if (!longEnough || file == null) return null;
        try {
            byte[] data = new byte[(int) file.length()];
            FileInputStream in = new FileInputStream(file);
            try {
                int off = 0;
                while (off < data.length) {
                    int n = in.read(data, off, data.length - off);
                    if (n < 0) break;
                    off += n;
                }
            } finally {
                in.close();
            }
            return data.length == 0 ? null : data;
        } catch (IOException e) {
            return null;
        }
    }

    public void cancel() {
        if (recorder == null) return;
        MediaRecorder r = recorder;
        recorder = null;
        try { r.stop(); } catch (RuntimeException ignored) { }
        r.release();
    }
}
