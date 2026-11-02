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

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.DecelerateInterpolator;

import com.android.dialer.R;

/** A fixed number plate under a rotating, perforated finger wheel. */
public final class RotaryDialView extends View {
  private static final float STOP_ANGLE = 30;
  private static final float HOLE_SPACING = 30;
  private static final float HOLE_RADIUS = 12;
  private static final float HOLE_ORBIT = 73;
  private static final int LOST_MOTION_MS = 100;
  private static final int PULSE_MS = 100;
  private static final String[] NUMBERS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0"};

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path fingerWheel = new Path();
  private final Path fingerStop = new Path();
  private final RectF bezelBounds = new RectF(-96, -96, 96, 96);
  private final float[] holeX = new float[10];
  private final float[] holeY = new float[10];
  private final Shader chrome = new LinearGradient(-100, -100, 100, 100,
      new int[] {0xff55585c, 0xfff9faf7, 0xff85898c, 0xffffffff, 0xff62666a},
      new float[] {0, 0.25f, 0.48f, 0.7f, 1}, Shader.TileMode.CLAMP);
  private final Shader wheelSheen = new LinearGradient(-50, -90, 60, 90,
      new int[] {0xff36373a, 0xff121315, 0xff242527}, null, Shader.TileMode.CLAMP);
  private final Shader plateShade = new RadialGradient(-25, -40, 150,
      0xfffff9e3, 0xffc9c1a5, Shader.TileMode.CLAMP);
  private final String windLabel;
  private final String releaseLabel;
  private final String callLabel;
  private final String returningLabel;
  private final String restLabel;
  private String woundLabel;
  private float rotation;
  private int wound;
  private int touchHole;
  private int touchPointer = -1;
  private float previousTouchAngle;
  private float touchRotation;
  private boolean returning;
  private ValueAnimator animator;
  private RotaryDialSound sound;
  private Listener listener;
  private long returnStarted;
  private int returnDuration;
  private int returnPulses;
  private float returnAngle;
  private boolean settled;
  private final Runnable returnFrame = new Runnable() {
    @Override
    public void run() {
      long elapsed = SystemClock.uptimeMillis() - returnStarted;
      if (elapsed < returnDuration) {
        rotation = Math.max(0, returnAngle - elapsed * HOLE_SPACING / PULSE_MS);
      } else {
        if (!settled) {
          settled = true;
          if (sound != null) {
            sound.stop();
          }
          play(RotaryDialSound.THUNK);
        }
        rotation = -0.8f * Math.max(0, 1 - (elapsed - returnDuration) / 45f);
      }
      invalidate();
      if (elapsed < returnDuration + 45) {
        postOnAnimation(this);
      } else {
        int pulses = returnPulses;
        reset();
        if (pulses > 0 && listener != null) {
          listener.onDigit(pulses == 10 ? 0 : pulses);
        }
      }
    }
  };

  interface Listener {
    void onDigit(int digit);

    void onBackspace();

    void onCallFocus();
  }

  public RotaryDialView(Context context, AttributeSet attrs) {
    super(context, attrs);
    setFocusable(true);
    setFocusableInTouchMode(true);
    setClickable(true);
    setSoundEffectsEnabled(false);
    setContentDescription(context.getString(R.string.rotary_dial_description));
    windLabel = context.getString(R.string.rotary_dial_wind);
    releaseLabel = context.getString(R.string.rotary_dial_release);
    callLabel = context.getString(R.string.rotary_dial_call);
    returningLabel = context.getString(R.string.rotary_dial_returning);
    restLabel = context.getString(R.string.rotary_dial_rest);
    woundLabel = windLabel;
    fingerWheel.setFillType(Path.FillType.EVEN_ODD);
    fingerWheel.addCircle(0, 0, 88, Path.Direction.CW);
    for (int i = 0; i < 10; i++) {
      double angle = Math.toRadians(STOP_ANGLE - (i + 2) * HOLE_SPACING);
      holeX[i] = HOLE_ORBIT * (float) Math.cos(angle);
      holeY[i] = HOLE_ORBIT * (float) Math.sin(angle);
      fingerWheel.addCircle(holeX[i], holeY[i], HOLE_RADIUS, Path.Direction.CW);
    }
    fingerStop.moveTo(55, 40);
    fingerStop.lineTo(86, 50);
    fingerStop.lineTo(72, 66);
    fingerStop.close();
  }

  void setListener(Listener listener) {
    this.listener = listener;
  }

  @Override
  protected void onAttachedToWindow() {
    super.onAttachedToWindow();
    if (isShown() && sound == null) {
      sound = new RotaryDialSound(getContext());
    }
  }

  @Override
  protected void onDetachedFromWindow() {
    reset();
    closeSound();
    super.onDetachedFromWindow();
  }

  @Override
  protected void onVisibilityChanged(View changedView, int visibility) {
    super.onVisibilityChanged(changedView, visibility);
    if (windLabel == null) {
      return;
    }
    if (!isShown()) {
      reset();
      closeSound();
    } else if (isAttachedToWindow() && sound == null) {
      sound = new RotaryDialSound(getContext());
    }
  }

  void reset() {
    cancelAnimation();
    removeCallbacks(returnFrame);
    if (touchPointer != -1 && getParent() != null) {
      getParent().requestDisallowInterceptTouchEvent(false);
    }
    returning = false;
    wound = 0;
    rotation = 0;
    touchHole = 0;
    touchRotation = 0;
    touchPointer = -1;
    woundLabel = windLabel;
    setStateDescription(restLabel);
    if (sound != null) {
      sound.stop();
    }
    invalidate();
  }

  private void closeSound() {
    if (sound != null) {
      sound.close();
      sound = null;
    }
  }

  private void cancelAnimation() {
    if (animator != null) {
      animator.removeAllListeners();
      animator.removeAllUpdateListeners();
      animator.cancel();
      animator = null;
    }
  }

  private void play(int effect) {
    if (sound != null) {
      sound.play(effect);
    }
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.save();
    float scale = Math.min(getWidth(), getHeight()) / 204f;
    canvas.translate(getWidth() / 2f, getHeight() / 2f);
    canvas.scale(scale, scale);
    paint.setStyle(Paint.Style.FILL);
    paint.setShader(chrome);
    canvas.drawCircle(0, 0, 98, paint);
    paint.setShader(null);
    paint.setColor(0xff404145);
    canvas.drawCircle(0, 0, 93, paint);
    paint.setShader(plateShade);
    canvas.drawCircle(0, 0, 90, paint);
    paint.setShader(null);
    paint.setColor(0xff24201b);
    paint.setTextAlign(Paint.Align.CENTER);
    paint.setTypeface(Typeface.DEFAULT_BOLD);
    paint.setTextSize(13);
    for (int i = 0; i < 10; i++) {
      canvas.drawText(NUMBERS[i], holeX[i], holeY[i] + 4.5f, paint);
    }

    canvas.save();
    canvas.rotate(rotation);
    paint.setShader(wheelSheen);
    canvas.drawPath(fingerWheel, paint);
    paint.setShader(null);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1.2f);
    paint.setColor(0xff66676a);
    for (int i = 0; i < 10; i++) {
      canvas.drawCircle(holeX[i], holeY[i], HOLE_RADIUS + 0.5f, paint);
    }
    paint.setStrokeWidth(0.7f);
    paint.setColor(0xff050608);
    for (int i = 0; i < 10; i++) {
      canvas.drawCircle(holeX[i], holeY[i], HOLE_RADIUS - 0.5f, paint);
    }
    if (wound > 0 && !returning) {
      paint.setColor(0xffa1e7ef);
      paint.setStrokeWidth(2);
      canvas.drawCircle(holeX[wound - 1], holeY[wound - 1], HOLE_RADIUS + 2, paint);
    }
    canvas.restore();

    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0xff080909);
    canvas.drawCircle(1, 2, 39, paint);
    paint.setShader(chrome);
    canvas.drawCircle(0, 0, 38, paint);
    paint.setShader(plateShade);
    canvas.drawCircle(0, 0, 36, paint);
    paint.setShader(null);
    paint.setColor(0xff594534);
    paint.setTextSize(6.6f);
    canvas.drawText(returning ? returningLabel : woundLabel, 0, -4, paint);
    canvas.drawText(releaseLabel, 0, 7, paint);
    paint.setTextSize(4.5f);
    canvas.drawText(callLabel, 0, 19, paint);

    canvas.save();
    canvas.translate(1, 2);
    paint.setColor(0x99000000);
    canvas.drawPath(fingerStop, paint);
    canvas.restore();
    paint.setShader(chrome);
    canvas.drawPath(fingerStop, paint);
    paint.setShader(null);
    paint.setStyle(Paint.Style.STROKE);
    paint.setColor(0xff999da0);
    paint.setStrokeWidth(0.6f);
    canvas.drawPath(fingerStop, paint);
    if (isFocused()) {
      paint.setColor(0xffa1e7ef);
      paint.setStrokeWidth(1.5f);
      canvas.drawOval(bezelBounds, paint);
    }
    canvas.restore();
  }

  private static float woundAngle(int steps) {
    return steps == 0 ? 0 : (steps + 1) * HOLE_SPACING;
  }

  private void announceWound() {
    woundLabel = wound == 0 ? windLabel
        : getResources().getString(R.string.rotary_dial_wound, NUMBERS[wound - 1]);
    setStateDescription(wound == 0 ? restLabel : woundLabel);
    announceForAccessibility(wound == 0 ? restLabel : woundLabel);
  }

  private void step(int direction) {
    if (returning || touchPointer != -1) {
      return;
    }
    if (direction < 0 && wound == 0) {
      play(RotaryDialSound.ERASE);
      if (listener != null) {
        listener.onBackspace();
      }
      return;
    }
    if (direction > 0 && wound == 10) {
      play(RotaryDialSound.THUNK);
      return;
    }
    wound += direction;
    announceWound();
    play(RotaryDialSound.WIND);
    cancelAnimation();
    animator = ValueAnimator.ofFloat(rotation, woundAngle(wound));
    animator.setDuration(75);
    animator.setInterpolator(new DecelerateInterpolator());
    animator.addUpdateListener(value -> {
      rotation = (float) value.getAnimatedValue();
      invalidate();
    });
    animator.start();
  }

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
      step(keyCode == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
      return true;
    }
    if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
      if (event.getRepeatCount() == 0 && !returning) {
        performClick();
      }
      return true;
    }
    return super.onKeyDown(keyCode, event);
  }

  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP
        || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
      return true;
    }
    return super.onKeyUp(keyCode, event);
  }

  @Override
  public boolean performClick() {
    super.performClick();
    if (!returning && touchPointer == -1) {
      if (wound > 0) {
        // Finish any step easing before the constant-speed return.
        rotation = woundAngle(wound);
        release(wound);
      } else if (listener != null) {
        listener.onCallFocus();
      }
    }
    return true;
  }

  private void release(int pulses) {
    cancelAnimation();
    if (rotation <= 0) {
      reset();
      return;
    }
    returning = true;
    setStateDescription(returningLabel);
    returnAngle = rotation;
    returnPulses = pulses;
    returnDuration = pulses > 0 ? LOST_MOTION_MS + pulses * PULSE_MS
        : Math.max(80, Math.round(rotation / HOLE_SPACING * PULSE_MS));
    returnStarted = SystemClock.uptimeMillis();
    settled = false;
    if (sound != null) {
      sound.playReturn(pulses);
    }
    postOnAnimation(returnFrame);
  }

  private float touchAngle(MotionEvent event, int index) {
    return (float) Math.toDegrees(Math.atan2(event.getY(index) - getHeight() / 2f,
        event.getX(index) - getWidth() / 2f));
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    if (returning) {
      return true;
    }
    switch (event.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        reset();
        float scale = Math.min(getWidth(), getHeight()) / 204f;
        float x = (event.getX() - getWidth() / 2f) / scale;
        float y = (event.getY() - getHeight() / 2f) / scale;
        for (int i = 0; i < 10; i++) {
          if (Math.hypot(x - holeX[i], y - holeY[i]) <= HOLE_RADIUS + 3) {
            touchHole = i + 1;
            touchPointer = event.getPointerId(0);
            previousTouchAngle = touchAngle(event, 0);
            getParent().requestDisallowInterceptTouchEvent(true);
            requestFocus();
            return true;
          }
        }
        return false;
      case MotionEvent.ACTION_MOVE:
        int index = event.findPointerIndex(touchPointer);
        if (index < 0) {
          return true;
        }
        float angle = touchAngle(event, index);
        float delta = (angle - previousTouchAngle + 540) % 360 - 180;
        previousTouchAngle = angle;
        touchRotation = Math.max(0, Math.min(woundAngle(touchHole), touchRotation + delta));
        rotation = touchRotation;
        int steps = Math.max(0, Math.min(touchHole, (int) (rotation / HOLE_SPACING) - 1));
        if (steps != wound) {
          wound = steps;
          play(RotaryDialSound.WIND);
          announceWound();
        }
        invalidate();
        return true;
      case MotionEvent.ACTION_POINTER_UP:
        if (event.getPointerId(event.getActionIndex()) != touchPointer) {
          return true;
        }
        // The active finger leaving ends the gesture, even if another finger remains down.
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL:
        if (touchPointer != -1) {
          int pulses = event.getActionMasked() != MotionEvent.ACTION_CANCEL
              && rotation >= woundAngle(touchHole) - 5 ? touchHole : 0;
          if (pulses > 0) {
            rotation = woundAngle(pulses);
          }
          touchPointer = -1;
          touchRotation = 0;
          getParent().requestDisallowInterceptTouchEvent(false);
          release(pulses);
        }
        return true;
      default:
        return true;
    }
  }

  @Override
  public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
    super.onInitializeAccessibilityNodeInfo(info);
    info.setClassName("android.widget.Button");
    info.setScrollable(true);
    info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(
        AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, 0, 10, wound));
    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
  }

  @Override
  public boolean performAccessibilityAction(int action, Bundle arguments) {
    if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
      step(action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1);
      return true;
    }
    return super.performAccessibilityAction(action, arguments);
  }
}
