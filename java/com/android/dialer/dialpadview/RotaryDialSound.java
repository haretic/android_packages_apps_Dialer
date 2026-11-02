/*
 * Copyright (C) 2026 The LineageOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.dialer.dialpadview;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;

import com.android.dialer.common.LogUtil;

/** Short, generated mechanical sounds. All track operations stay off the UI thread. */
final class RotaryDialSound {
  static final int WIND = 0;
  static final int THUNK = 1;
  static final int ERASE = 2;
  static final int RETURN = 3;
  static final int SLIP = 4;

  private static final int SAMPLE_RATE = 48000;
  private final Context context;
  private final HandlerThread thread = new HandlerThread("RotaryDialSound");
  private final Handler handler;
  private final AudioTrack[] tracks = new AudioTrack[5];
  private final short[][] returnBuffers = new short[10][];
  private boolean closed;

  RotaryDialSound(Context context) {
    this.context = context.getApplicationContext();
    thread.start();
    handler = new Handler(thread.getLooper());
    handler.post(this::prepare);
  }

  private void prepare() {
    try {
      for (int effect = 0; effect < tracks.length; effect++) {
        short[] pcm = synthesize(effect);
        AudioTrack track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build())
            .setAudioFormat(new AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.length * 2)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build();
        tracks[effect] = track;
        if (effect == RETURN) {
          for (int pulses = 1; pulses <= 10; pulses++) {
            returnBuffers[pulses - 1] = pulses == 10 ? pcm : synthesize(RETURN, pulses);
          }
        }
        if (track.write(pcm, 0, pcm.length) != pcm.length) {
          throw new IllegalStateException("Incomplete rotary sound buffer");
        }
      }
    } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException e) {
      LogUtil.w("RotaryDialSound.prepare", "Mechanical sounds unavailable", e);
      releaseTracks();
    }
  }

  private static short[] synthesize(int effect) {
    return synthesize(effect, 10);
  }

  private static short[] synthesize(int effect, int pulses) {
    boolean returning = effect == RETURN || effect == SLIP;
    int length = returning ? SAMPLE_RATE * 12 / 10 : SAMPLE_RATE / 20;
    short[] pcm = new short[length];
    int noise = 0x7213;
    for (int i = 0; i < length; i++) {
      double t = (double) i / SAMPLE_RATE;
      noise = noise * 1664525 + 1013904223;
      double random = (noise >> 16) / 32768.0;
      double sample;
      if (returning) {
        sample = (random * 0.018 + Math.sin(t * 2 * Math.PI * 155) * 0.012)
            * Math.min(1, t / 0.025);
        // The first 100 ms is lost motion; clicks fall halfway through each pulse interval.
        double clickTime = t - 0.15;
        if (effect == RETURN && clickTime >= 0 && clickTime < pulses * 0.1) {
          double pulse = clickTime % 0.1;
          sample += (random * 0.48 + Math.sin(pulse * 2 * Math.PI * 1900) * 0.25)
              * Math.exp(-pulse * 650);
        }
        if (effect == RETURN) {
          sample *= Math.max(0, Math.min(1, (0.1 + pulses * 0.1 - t) / 0.015));
        }
      } else {
        double frequency = effect == THUNK ? 180 : effect == ERASE ? 720 : 1400;
        double decay = effect == THUNK ? 110 : 260;
        double volume = effect == THUNK ? 0.16 : 0.26;
        sample = (random * 0.55 + Math.sin(t * 2 * Math.PI * frequency))
            * volume * Math.exp(-t * decay);
      }
      pcm[i] = (short) (Math.max(-1, Math.min(1, sample)) * 32767);
    }
    return pcm;
  }

  void play(int effect) {
    play(effect, 10);
  }

  void playReturn(int pulses) {
    play(pulses > 0 ? RETURN : SLIP, pulses);
  }

  private void play(int effect, int pulses) {
    if (closed || Settings.System.getInt(context.getContentResolver(),
        Settings.System.DTMF_TONE_WHEN_DIALING, 1) == 0) {
      return;
    }
    handler.post(() -> {
      AudioTrack track = tracks[effect];
      if (track != null) {
        try {
          track.stop();
          // Silence after this digit prevents extra pulses even if a UI frame is delayed.
          if (effect == RETURN) {
            short[] pcm = returnBuffers[pulses - 1];
            if (track.write(pcm, 0, pcm.length) != pcm.length) {
              throw new IllegalStateException("Incomplete rotary return buffer");
            }
          }
          track.setPlaybackHeadPosition(0);
          track.play();
        } catch (IllegalStateException e) {
          LogUtil.w("RotaryDialSound.play", "Mechanical sounds unavailable", e);
          releaseTracks();
        }
      }
    });
  }

  void stop() {
    if (!closed) {
      handler.post(() -> {
        for (AudioTrack track : tracks) {
          if (track != null) {
            track.stop();
          }
        }
      });
    }
  }

  void close() {
    if (closed) {
      return;
    }
    closed = true;
    handler.removeCallbacksAndMessages(null);
    handler.post(() -> {
      releaseTracks();
      thread.quitSafely();
    });
  }

  private void releaseTracks() {
    for (int i = 0; i < tracks.length; i++) {
      if (tracks[i] != null) {
        tracks[i].release();
        tracks[i] = null;
      }
    }
    for (int i = 0; i < returnBuffers.length; i++) {
      returnBuffers[i] = null;
    }
  }
}
