package com.vuzix.sample.buttonfocus;

import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

// optionnel, pour les démos
class WavRecorder {

    private static final String TAG = "VisioLingo";

    private final File file;
    private RandomAccessFile raf;
    private int sampleRate;
    private int channels;
    private int bitsPerSample;
    private long dataBytes = 0;
    private boolean started = false;
    private boolean stopped = false;

    WavRecorder(File file) {
        this.file = file;
    }

    File getFile() {
        return file;
    }

    void append(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        if (stopped) return;
        try {
            if (!started) {
                this.sampleRate = sampleRate;
                this.channels = channels;
                this.bitsPerSample = bitsPerSample;
                raf = new RandomAccessFile(file, "rw");
                raf.setLength(0);
                raf.write(new byte[44]); // en-tete provisoire, corrige dans stop()
                started = true;
                Log.d(TAG, "WAV start: " + file.getAbsolutePath()
                        + " (" + sampleRate + "Hz ch=" + channels + " " + bitsPerSample + "bit)");
            }
            raf.write(pcm);
            dataBytes += pcm.length;
        } catch (IOException e) {
            Log.e(TAG, "WAV append error", e);
        }
    }

    void flush() {
        if (raf == null || !started || stopped) return;
        try {
            long end = 44 + dataBytes;
            patchHeader();
            raf.seek(end);
        } catch (IOException e) {
            Log.e(TAG, "WAV flush error", e);
        }
    }

    void stop() {
        stopped = true;
        if (raf == null || !started) return;
        try {
            patchHeader();
            raf.close();
            Log.d(TAG, "WAV done: " + file.getAbsolutePath() + " (" + dataBytes + " octets)");
        } catch (IOException e) {
            Log.e(TAG, "WAV stop error", e);
        } finally {
            raf = null;
        }
    }

    private void patchHeader() throws IOException {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        raf.seek(0);
        raf.write(new byte[]{'R', 'I', 'F', 'F'});
        writeIntLE((int) (36 + dataBytes));
        raf.write(new byte[]{'W', 'A', 'V', 'E'});
        raf.write(new byte[]{'f', 'm', 't', ' '});
        writeIntLE(16);
        writeShortLE((short) 1); // PCM
        writeShortLE((short) channels);
        writeIntLE(sampleRate);
        writeIntLE(byteRate);
        writeShortLE((short) blockAlign);
        writeShortLE((short) bitsPerSample);
        raf.write(new byte[]{'d', 'a', 't', 'a'});
        writeIntLE((int) dataBytes);
    }

    private void writeIntLE(int v) throws IOException {
        raf.write(v & 0xff);
        raf.write((v >> 8) & 0xff);
        raf.write((v >> 16) & 0xff);
        raf.write((v >> 24) & 0xff);
    }

    private void writeShortLE(short v) throws IOException {
        raf.write(v & 0xff);
        raf.write((v >> 8) & 0xff);
    }
}
