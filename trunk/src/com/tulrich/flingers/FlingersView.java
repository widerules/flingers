/*
 * Copyright (C) 2007 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.tulrich.flingers;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.Vibrator;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;

// TODO:
// wings
// serialization
// look at power savings / better sleeping

/**
 * View that draws, takes keystrokes, etc. for a simple Flingers game.
 * 
 * @author tu@tulrich.com (Thatcher Ulrich)
 * Originally derived from LunarLander android sample.
 */
class FlingersView extends SurfaceView implements SurfaceHolder.Callback {
  public class Drawable implements Comparable {
    // Sort back to front.
    public int compareTo(Object o) {
      if (!(o instanceof Drawable)) {
        return 0;
      }
      Drawable d = (Drawable) o;
      float f0 = SortKey();
      float f1 = d.SortKey();
      if (f0 > f1) {
        return -1;
      } else if (f0 == f1) {
        return 0;
      } else {
        return 1;
      }
    }

    public float SortKey() {
      return 0;
    }
    public void Draw(Canvas canvas) {
    }
  };

  public class Circle extends Drawable {
    public float mX;
    public float mY;
    public float mZ;
    public float mRadius;
    public Paint mPaint;

    public Circle() {
    }

    public float SortKey() {
      return mZ;
    }

    public void set(float x, float y, float z, float radius, Paint paint) {
      mX = x;
      mY = y;
      mZ = z;
      mRadius = radius;
      mPaint = paint;
    }

    public void copy(Circle c) {
      mX = c.mX;
      mY = c.mY;
      mZ = c.mZ;
      mRadius = c.mRadius;
      mPaint = c.mPaint;
    }

    public void Draw(Canvas canvas) {
      canvas.drawCircle(mX, mY, mRadius, mPaint);
    }
  };

  private ArrayList<Drawable> mDisplayList = new ArrayList<Drawable>();
  private ArrayList<Circle> mCirclePool = new ArrayList<Circle>();
  private int mNextCircle = 0;

  private void DisplayListClear() {
    mDisplayList.clear();
    mNextCircle = 0;
  }

  private void DisplayListDraw(Canvas canvas) {
    // Sort the drawables.
    //
    // NOTE: this causes a link-time warning "unchecked or unsafe
    // operations".  Why?
    Collections.sort(mDisplayList);

    // Draw back-to-front.
    for (int i = 0; i < mDisplayList.size(); i++) {
      mDisplayList.get(i).Draw(canvas);
    }
  }

  private Circle NextCircle() {
    Circle c;
    if (mNextCircle >= mCirclePool.size()) {
      c = new Circle();
      mCirclePool.add(c);
    } else {
      c = mCirclePool.get(mNextCircle);
    }
    mNextCircle++;
    mDisplayList.add(c);
    return c;
  }

  private void AddCircle(Circle c) {
    NextCircle().copy(c);
  }

  private void AddCircle(float x, float y, float z, float radius, Paint paint) {
    NextCircle().set(x, y, z, radius, paint);
  }

  class Obj {
    public float mX = 0;
    public float mY = 0;
    public float mZ = 0;
    public boolean mAlive = true;

    public float mDX = 0;
    public float mDY = 0;
    public float mDZ = 0;

    public float mDirX = 0;
    public float mDirY = 0;
  };
  
  class Shot extends Obj {
    public static final float MAX_RADIUS = 40.0f;
    public boolean mBounced = false;

    public Shot(float x, float y, float z, float dx, float dy, float dz) {
      mX = x;
      mY = y;
      mZ = z;
      mDX = dx;
      mDY = dy;
      mDZ = dz;
    }
  };

  enum TargetState {
    EGG,
          
    // Larva
    LOOK_OPEN,
    LOOK_PRE_RISE,
    LOOK_RISE,
    LOOK_DWELL_BEFORE_DROP,
    LOOK_DROP,
    LOOK_CLOSE,

    LARVA_DIE,

    WALK,
    MUNCH,
    BURROW,
    SHRIVEL,

    // Pupa + Fly state
    PUPATE,
    FLY_RISE,
    FLY,
    FLY_LAY_EGG,
    FLY_ESCAPE,
  }
  
  class Target extends Obj {
    public float mMaxRadius;
    public float mTimer;
    public float mTravelTime;
    public Flower mFlowerToEat;
    public int mWalkedCount = 0;
    public int mEggsLaid = 0;

    TargetState mState;

    public Target(float x, float y, float dx, float dy, float maxRadius) {
      mState = TargetState.EGG;
      mX = x;
      mY = y;
      mDX = dx;
      mDY = dy;
      mMaxRadius = maxRadius;
      mTimer = 0;
    }
  };

  enum FlowerState {
    SPROUT,
    GROW,
    BUD,
    BLOOM,
    SEED,
  }
  
  class Flower extends Obj {
    public FlowerState mState;
    public float mTimer;
    
    public Flower(float x, float y) {
      mState = FlowerState.SPROUT;
      mTimer = 0;
      mX = x;
      mY = y;
    }
  };

  enum GameState {
    INVALID,
    ATTRACT,
    PLAYING,
    GAME_OVER,
  }

  enum ScorePhase {
    NONE,
    MESSAGE,
    SCORE_FLOWERS,
    MOVE_FLOWERS,
    ADD_EGGS,
  }

  private static float Clamp(float x, float minval, float maxval) {
    if (x < minval) {
      return minval;
    }
    if (x > maxval) {
      return maxval;
    }
    return x;
  }

  /**
   * Given x between min and max, returns a value t that is 0 when
   * x==min, 1 when x==max, and linearly ramps in between.
   */
  public static float MakeLerper(float x, float min, float max) {
    return Clamp((x - min) / (max - min), 0, 1);
  }
  
  /**
   * Returns a linear blend between min and max, according to t.
   */
  public static float Lerp(float t, float min, float max) {
    return min + (max - min) * t;
  }
  
  class FlingersThread extends Thread {
    /*
     * Goal condition constants
     */
    public static final int TARGET_ANGLE = 18; // > this angle means crash
    public static final int TARGET_BOTTOM_PADDING = 17; // px below gear
    public static final int TARGET_PAD_HEIGHT = 8; // how high above ground
    public static final int TARGET_SPEED = 28; // > this speed means crash
    public static final double TARGET_WIDTH = 1.6; // width of target

    /*
     * Member (state) fields
     */

    /**
     * Current height of the surface/canvas.
     * 
     * @see #setSurfaceSize
     */
    private int mCanvasHeight = 1;

    /**
     * Current width of the surface/canvas.
     * 
     * @see #setSurfaceSize
     */
    private int mCanvasWidth = 1;

    /** Message handler used by thread to interact with TextView */
    private Handler mHandlerText;
    private Handler mHandlerScore;

    private Vibrator mVibrator;

    /** Used to figure out elapsed time between frames */
    private long mLastTime;

    private Paint mBlackPaint;
    private Paint mWhitePaint;
    private Paint mSkyPaint;
    private Paint mGroundPaint;
    private Paint mShadowPaint;
    private Paint mHolePaint;
    private Paint mScorePaint;

    private Paint mLarvaPaint1;
    private Paint mLarvaPaint2;
    private Paint mFlyPaint1;
    private Paint mFlyPaint2;
    private Paint mShotPaint;

    private Paint mAnchorPaint;
    private Paint mBandPaint;

    private Paint mFlowerStemPaint;
    private Paint mFlowerPetalPaint;

    /** The state of the game */
    private GameState mState = GameState.INVALID;
    private boolean mPaused = false;
    private boolean mPausedDrawn = false;
    private float mGameTimer = 0;
    private ScorePhase mScorePhase = ScorePhase.NONE;
    private int mScoreFlower = 0;
    private int mFlowerToMove = 0;
    private int mEggsToAdd = 0;
    private int mLastBonusFlowerScore = 0;

    /** Indicate whether the surface has been created & is ready to draw */
    private boolean mRun = false;

    /** Scratch rect object. */
    private RectF mScratchRect;

    /** Handle to the surface manager object we interact with */
    private SurfaceHolder mSurfaceHolder;

    /** active shots */
    private ArrayList<Shot> mShots = new ArrayList<Shot>();

    /** active targets */
    private ArrayList<Target> mTargets = new ArrayList<Target>();

    /** active flowers */
    private ArrayList<Flower> mFlowers = new ArrayList<Flower>();

    /** Shooter spring. */
    private static final float SPRING_GRAB_DISTANCE = 60;
    private static final float SPRING_HOTSPOT_SETBACK = 40;
    private static final float SPRING_RELOAD_TIME = 0.5f;
    private static final int SPRING_RESTING = 0;
    private static final int SPRING_HELD = 1;
    private static final int SPRING_RELEASED = 2;
    private static final int SPRING_EMITTED = 3;
    private int mSpringState = SPRING_RESTING;
    private int mScore = 0;
    private int mLevel = 1;

    private static final float ANCHOR_HEIGHT = 150;
    private static final float ANCHOR_WIDTH = 400;
    private static final float BAND_MAX_WIDTH = 8;
    private static final float BAND_REST_LENGTH = 40;
    private static final float BAND_RELEASE_LENGTH = (ANCHOR_WIDTH / 2) + 20;
    private static final float BAND_K = 200.0f;
    private static final float BAND_LOADED_MASS = 2.0f;
    private static final float BAND_UNLOADED_MASS = 1.0f;

    private static final float TARGET_MIN_CONE_Y = 0;
    private static final float TARGET_MIN_Y = 200;
    private static final float TARGET_MAX_Y = 2200;
    private static final float TARGET_MAX_RADIUS = 200;
    private static final float TARGET_MAX_HOLE_RADIUS = 100.0f;

    private static final float TARGET_WALK_SPEED = 400;
    private static final float TARGET_WALK_FREQ = 9;  // radians/sec for oscillating

    private static final float TARGET_BURROW_FREQ = 30;  // radians/sec for burrowing
    private static final float TARGET_BURROW_MAG = 30.0f;

    private static final float TARGET_FLY_SPEED = 800;
    private static final float TARGET_FLY_FREQ = 30;  // radians/sec for flapping

    private static final float SHOT_DZ_FACTOR = 0.15f;
    private static final float GRAVITY = 250.0f;

    private static final float EGG_PREDELAY = 0.5f;

    private float mTouchHeight;
    private float mHorizonHeight;

    private float mTargetMaxX;

    private float mAnchor0X;
    private float mAnchor0Y;
    private float mAnchor1X;
    private float mAnchor1Y;

    private float mBand0Length = 100;
    private float mBand1Length = 100;

    private float mStretchEffectLength;

    private float mHeadX;
    private float mHeadY;
    private float mHeadVelX;
    private float mHeadVelY;
    private float mHotspotX;
    private float mHotspotY;
    private float mReloadTime;

    public FlingersThread(SurfaceHolder surfaceHolder, Context context,
                          Handler handlerText, Handler handlerScore) {
      // get handles to some important objects
      mSurfaceHolder = surfaceHolder;
      mHandlerText = handlerText;
      mHandlerScore = handlerScore;
      mContext = context;

      mVibrator = (Vibrator) context.getSystemService(context.VIBRATOR_SERVICE);

      Resources res = context.getResources();

      mBlackPaint = new Paint();
      mBlackPaint.setAntiAlias(true);
      mBlackPaint.setARGB(255, 0, 0, 0);

      mWhitePaint = new Paint();
      mWhitePaint.setAntiAlias(true);
      mWhitePaint.setARGB(255, 255, 255, 255);

      mSkyPaint = new Paint();
      mSkyPaint.setARGB(255, 80, 80, 200);

      mGroundPaint = new Paint();
      mGroundPaint.setARGB(255, 180, 150, 100);

      mShadowPaint = new Paint();
      mShadowPaint.setAntiAlias(true);
      mShadowPaint.setARGB(255, 80, 70, 50);

      mHolePaint = new Paint();
      mHolePaint.setAntiAlias(true);
      mHolePaint.setARGB(255, 50, 100, 50);

      mScorePaint = new Paint();
      mHolePaint.setAntiAlias(true);
      mScorePaint.setARGB(200, 255, 200, 200);
      mScorePaint.setTextSize(20.0f);

      mLarvaPaint1 = new Paint();
      mLarvaPaint1.setAntiAlias(true);
      mLarvaPaint1.setARGB(255, 0, 200, 0);

      mLarvaPaint2 = new Paint();
      mLarvaPaint2.setAntiAlias(true);
      mLarvaPaint2.setARGB(255, 0, 255, 0);

      mFlyPaint1 = new Paint();
      mFlyPaint1.setAntiAlias(true);
      mFlyPaint1.setARGB(255, 0, 0, 0);

      mFlyPaint2 = new Paint();
      mFlyPaint2.setAntiAlias(true);
      mFlyPaint2.setARGB(255, 255, 255, 0);

      mShotPaint = new Paint();
      mShotPaint.setAntiAlias(true);
      mShotPaint.setARGB(255, 0, 128, 0);

      mAnchorPaint = new Paint();
      mAnchorPaint.setAntiAlias(true);
      mAnchorPaint.setARGB(255, 255, 80, 100);

      mBandPaint = new Paint();
      mBandPaint.setAntiAlias(true);
      mBandPaint.setStrokeCap(Paint.Cap.ROUND);
      mBandPaint.setARGB(255, 255, 255, 100);

      mFlowerStemPaint = new Paint();
      mFlowerStemPaint.setAntiAlias(true);
      mFlowerStemPaint.setARGB(255, 0, 200, 0);

      mFlowerPetalPaint = new Paint();
      mFlowerPetalPaint.setAntiAlias(true);
      mFlowerPetalPaint.setARGB(255, 200, 200, 0);

      mScratchRect = new RectF(0, 0, 0, 0);
    }

    private void GameReset() {
      synchronized (mSurfaceHolder) {
        mScore = 0;
        AddScore(0);
        mLevel = 1;
        mLastBonusFlowerScore = 0;
        GameInitLevel();
        SpringReset();
        
        mLastTime = System.currentTimeMillis() + 100;
      }
    }

    /**
     * Pauses the physics update & animation.
     */
    public void Pause() {
      synchronized (mSurfaceHolder) {
        mPaused = true;
        mPausedDrawn = false;
        SetMessage("Paused -- Touch Screen To Resume");
      }
    }

    /**
     * Pauses the physics update & animation, and shows the About
     * text.
     */
    public void About() {
      synchronized (mSurfaceHolder) {
        mPaused = true;
        mPausedDrawn = false;
        Resources res = mContext.getResources();
        SetMessage(res.getText(R.string.msg_about));
      }
    }

    /**
     * Restores game state from the indicated Bundle. Typically called when
     * the Activity is being restored after having been previously
     * destroyed.
     * 
     * @param savedState Bundle containing the game state
     */
    public synchronized void restoreState(Bundle savedState) {
      synchronized (mSurfaceHolder) {
        GameSetState(GameState.INVALID);
      }
    }

    @Override
    public void run() {
      while (mRun) {
        Canvas c = null;
        try {
          c = mSurfaceHolder.lockCanvas(null);
          synchronized (mSurfaceHolder) {
            if (!mPaused) {
              GameUpdate();
              GameDraw(c);
            } else {
              if (!mPausedDrawn) {
                GameDraw(c);
                mPausedDrawn = true;
              }
            }
          }
        } finally {
          // do this in a finally so that if an exception is thrown
          // during the above, we don't leave the Surface in an
          // inconsistent state
          if (c != null) {
            mSurfaceHolder.unlockCanvasAndPost(c);
          }
        }
      }
    }

    /**
     * Dump game state to the provided Bundle. Typically called when the
     * Activity is being suspended.
     * 
     * @return Bundle with this view's state
     */
    public Bundle saveState(Bundle map) {
      synchronized (mSurfaceHolder) {
        if (map != null) {
          // TODO
        }
      }
      return map;
    }

    /**
     * Used to signal the thread whether it should be running or not.
     * Passing true allows the thread to run; passing false will shut it
     * down if it's already running. Calling start() after this was most
     * recently called with false will result in an immediate shutdown.
     * 
     * @param b true to run, false to shut down
     */
    public void setRunning(boolean b) {
      mRun = b;
    }

    public void ShowMessage(String id, CharSequence message) {
      Handler h = id == "text" ? mHandlerText : mHandlerScore;
      Message msg = h.obtainMessage();
      Bundle b = new Bundle();
      b.putString("text", message.toString());
      b.putInt("viz", View.VISIBLE);
      msg.setData(b);
      h.sendMessage(msg);
    }

    public void SetMessage(CharSequence message) {
      synchronized (mSurfaceHolder) {
        /*
         * Since the View that actually renders that text is part of the
         * main View hierarchy and not owned by this thread, we can't
         * touch the state of that View.  Instead we use a Message +
         * Handler to relay commands to the main thread, which updates
         * the user-text View.
         */
        Resources res = mContext.getResources();

        Message msg = mHandlerText.obtainMessage();
        Bundle b = new Bundle();
        b.putString("text", message.toString());
        if (message.length() > 0) {
          b.putInt("viz", View.VISIBLE);
        } else {
          b.putInt("viz", View.INVISIBLE);
        }
        msg.setData(b);
        mHandlerText.sendMessage(msg);
      }
    }

    private void GameInitLevel() {
      // Create bugs.
      mTargets.clear();
      for (int i = 0; i < 5; i++) {
        Target t = TargetCreate();
        if (t != null) {
          t.mTimer = Lerp((float) Math.random(),
                          -2 * EGG_PREDELAY, -EGG_PREDELAY);
        }
      }

      // Create flowers.
      mFlowers.clear();
      while (mFlowers.size() < 5) {
        Flower f = FlowerCreate();
        f.mTimer = Lerp((float) Math.random(), -1.0f, FLOWER_SPROUT_TIME);
      }
    }

    public void GameSetState(GameState state) {
      synchronized (mSurfaceHolder) {
        GameState previousState = mState;
        mState = state;
        mGameTimer = 0;
        Unpause();

        switch (mState) {
          case INVALID:
            break;
          case ATTRACT:
            if (previousState == GameState.INVALID) {
              GameReset();
            }
            SetMessage("Touch Screen To Start");
            break;
          case PLAYING:
            GameReset();
            SetMessage("");
            break;
          case GAME_OVER:
            SetMessage("Game Over");
            SpringRelease();
            break;
        }
      }
    }

    /* Callback invoked when the surface dimensions change. */
    public void setSurfaceSize(int width, int height) {
      // synchronized to make sure these all change atomically
      synchronized (mSurfaceHolder) {
        mCanvasWidth = width;
        mCanvasHeight = height;

        float midx = width / 2;
        mAnchor0X = -ANCHOR_WIDTH / 2;
        mAnchor1X = ANCHOR_WIDTH / 2;
        mAnchor0Y = ANCHOR_HEIGHT;
        mAnchor1Y = mAnchor0Y;

        mTouchHeight = Project(mAnchor0X, mAnchor0Y + 50, 0).y;
        mHorizonHeight = Project(0, 10000, 0).y;

        float maxTargetScreenY = Project(0, TARGET_MAX_Y, 0).y;
        mTargetMaxX = (float) Math.abs(Unproject(0, maxTargetScreenY).x);

        SpringReset();
      }
    }

    /**
     * Resumes from a pause.
     */
    public void Unpause() {
      // Move the real time clock up to now
      synchronized (mSurfaceHolder) {
        mLastTime = System.currentTimeMillis() + 100;
        SetMessage("");
        mPaused = false;
      }
    }

    boolean DoStartGameInput() {
      if (mState == GameState.ATTRACT) {
        GameSetState(GameState.PLAYING);
        return true;
      } else if (mState == GameState.GAME_OVER && mGameTimer > 3.0f) {
        GameSetState(GameState.PLAYING);
        return true;
      }
      return false;
    }

    boolean doTouch(MotionEvent event) {
      synchronized (mSurfaceHolder) {
        float x = event.getX();
        float y = event.getY();

        if (mState == GameState.PLAYING) {
          if (mPaused) {
            Unpause();
            return true;
          }
          switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
              SpringHold(x, y);
              FlingerMove(x, y);
              break;
            case MotionEvent.ACTION_MOVE:
              FlingerMove(x, y);
              break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
              FlingerMove(x, y);
              SpringRelease();
              break;
          }
          return true;
        } else if (DoStartGameInput()) {
          return true;
        }

        return false;
      }
    }
      
    /**
     * Handles a key-down event.
     * 
     * @param keyCode the key that was pressed
     * @param msg the original event object
     * @return true
     */
    boolean doKeyDown(int keyCode, KeyEvent msg) {
      synchronized (mSurfaceHolder) {
        boolean okStart = false;
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) okStart = true;
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) okStart = true;
        if (keyCode == KeyEvent.KEYCODE_S) okStart = true;

        boolean center = (keyCode == KeyEvent.KEYCODE_DPAD_UP);

        if (mState == GameState.PLAYING) {
          if (mPaused) {
            if (okStart) {
              Unpause();
              return true;
            }
          }
          return false;
        } else if (okStart && DoStartGameInput()) {
          return true;
        }

        return false;
      }
    }

    /**
     * Handles a key-up event.
     * 
     * @param keyCode the key that was pressed
     * @param msg the original event object
     * @return true if the key was handled and consumed, or else false
     */
    boolean doKeyUp(int keyCode, KeyEvent msg) {
      boolean handled = false;

      synchronized (mSurfaceHolder) {
      }

      return handled;
    }

    private void ShotDraw(Canvas canvas, float x, float y, float worldY, float radius) {
      AddCircle(x, y, worldY, radius, mShotPaint);
    }

    private void ShotDrawShadow(Canvas canvas, float x, float y, float zscale, float radius) {
      mScratchRect.set(x - radius * zscale, y, x + radius * zscale, y + radius * zscale * 1.2f);
      canvas.drawOval(mScratchRect, mShadowPaint);
    }

    private void TargetHoleDraw(Canvas canvas, float x, float y, float radius) {
      Coord top = Project(x, y + radius, 0);
      Coord bottom = Project(x, y - radius, 0);
      Coord left = Project(x - radius, y, 0);
      Coord right = Project(x + radius, y, 0);

      mScratchRect.set(left.x, top.y, right.x, bottom.y);
      canvas.drawOval(mScratchRect, mHolePaint);
    }

    private static final float TARGET_HEAD_RADIUS = 80.0f;
    private static final float TARGET_BODY_RADIUS = 90.0f;
    private static final float TARGET_TAIL_RADIUS = 80.0f;
    private static final float TARGET_EGG_RADIUS = 30.0f;
    private static final float TARGET_RISE_HEIGHT = 15.0f;
    private static final float TARGET_RISE_HEAD_HEIGHT = 90.0f;
    private static final float TARGET_WALK_HEAD_OFFSET = 50.0f;
    private static final float TARGET_WALK_HEAD_HEIGHT = 0.0f;
    private static final float TARGET_WALK_BODY_OFFSET = 25.0f;
    private static final float TARGET_WALK_BODY_HEIGHT = 35.0f;
    private static final float TARGET_WALK_TAIL_OFFSET = 50.0f;
    private static final float TARGET_BURROW_OFFSET = 55.0f;
    private static final float TARGET_FLY_HEIGHT = 180.0f;
    private static final float TARGET_HEAD_FLY_HEIGHT = 90.0f;
    private static final float TARGET_HEAD_FLY_OFFSET = 20.0f;
    private static final float TARGET_TAIL_FLY_HEIGHT = -60.0f;
    private static final float TARGET_TAIL_FLY_OFFSET = -50.0f;
    

    private void TargetHeadDraw(Canvas canvas, float x, float y, float z) {
      Coord c = Project(x, y, z);
      AddCircle(c.x, c.y, y, c.z * TARGET_HEAD_RADIUS, mLarvaPaint1);
    }

    private void TargetBodyDraw(Canvas canvas, float x, float y, float z) {
      Coord c = Project(x, y, z);
      AddCircle(c.x, c.y, y, c.z * TARGET_BODY_RADIUS, mLarvaPaint2);
    }
    
    private void TargetTailDraw(Canvas canvas, float x, float y, float z) {
      Coord c = Project(x, y, z);
      AddCircle(c.x, c.y, y, c.z * TARGET_TAIL_RADIUS, mLarvaPaint1);
    }
    
    private void TargetHeadDrawFly(Canvas canvas, float x, float y, float z) {
      Coord c = Project(x, y, z);
      AddCircle(c.x, c.y, y, c.z * TARGET_HEAD_RADIUS, mFlyPaint1);
    }
    
    private void TargetBodyDrawFly(Canvas canvas, float x, float y, float z) {
      Coord c = Project(x, y, z);
      AddCircle(c.x, c.y, y, c.z * TARGET_BODY_RADIUS, mFlyPaint2);
    }
    
    private void TargetTailDrawFly(Canvas canvas, float x, float y, float z) {
      Coord c = Project(x, y, z);
      AddCircle(c.x, c.y, y, c.z * TARGET_TAIL_RADIUS, mFlyPaint1);
    }
    
    private void TargetDraw(Canvas canvas, Target t) {
      TargetHoleDraw(canvas, t.mX, t.mY, TargetGetHoleRadius(t));

      float headX = 0;
      float headY = 0;
      float headZ = 0;
      float tailX = 0;
      float tailY = 0;
      float tailZ = 0;

      switch (t.mState) {
        case EGG: {
          // Show a little nubbin above ground.
          Coord c = Project(t.mX, t.mY, 0);
          AddCircle(c.x, c.y, t.mY, 10.0f * c.z, mWhitePaint);
          break;
        }

        case LOOK_OPEN:
        case LOOK_CLOSE:
        case LARVA_DIE:
        case LOOK_PRE_RISE:
          break;

        case LOOK_RISE:
        case LOOK_DROP: {
          float f;
          if (t.mState == TargetState.LOOK_DROP) {
            f = MakeLerper(t.mTimer, TARGET_DROP_TIME, 0);
          } else {
            f = MakeLerper(t.mTimer, 0, TARGET_RISE_TIME);
          }
          float z = t.mZ + (f - 1) * TARGET_RISE_HEIGHT;
          TargetHeadDraw(canvas, t.mX, t.mY, z + TARGET_RISE_HEAD_HEIGHT);
          if (f > 0.5f) {
            TargetBodyDraw(canvas, t.mX, t.mY, z);
          }
          break;
        }

        case LOOK_DWELL_BEFORE_DROP: {
          float z = t.mZ;
          TargetBodyDraw(canvas, t.mX, t.mY, z);
          TargetHeadDraw(canvas, t.mX, t.mY, z + TARGET_RISE_HEAD_HEIGHT);
          break;
        }

        case WALK: {
          float freq = TARGET_WALK_FREQ * GameWalkSpeedFactor();
          float sin0 = Sinf(t.mTimer * freq + (float) Math.PI);
          float sin1 = Sinf(t.mTimer * freq + (float) Math.PI / 2);
          float sin2 = Sinf(t.mTimer * freq + (float) Math.PI);

          float rise = sin0;

          float hfactor = 1 + sin0 * 0.50f;
          float vfactor = 1 + sin0 * 0.2f;
          TargetHeadDraw(canvas,
                         t.mX + t.mDirX * TARGET_WALK_HEAD_OFFSET * hfactor,
                         t.mY + t.mDirY * TARGET_WALK_HEAD_OFFSET * hfactor,
                         t.mZ + TARGET_HEAD_RADIUS + TARGET_WALK_HEAD_HEIGHT * vfactor);

          hfactor = sin1 * 0.3f;
          vfactor = (1 - rise);
          TargetBodyDraw(canvas,
                         t.mX + t.mDirX * TARGET_WALK_BODY_OFFSET * hfactor,
                         t.mY + t.mDirY * TARGET_WALK_BODY_OFFSET * hfactor,
                         t.mZ + TARGET_BODY_RADIUS + TARGET_WALK_BODY_HEIGHT * vfactor);

          hfactor = 1 + sin2 * 0.50f;
          vfactor = 0 + sin2 * 0.1f;
          TargetTailDraw(canvas,
                         t.mX - t.mDirX * TARGET_WALK_TAIL_OFFSET * hfactor,
                         t.mY - t.mDirY * TARGET_WALK_TAIL_OFFSET * hfactor,
                         t.mZ + TARGET_TAIL_RADIUS);
          break;
        }

        case BURROW: {
          float f = MakeLerper(t.mTimer, 0, TARGET_BURROW_TIME);
          float sin0 = Sinf(t.mTimer * TARGET_BURROW_FREQ);
          float z = t.mZ + 1.5f * TARGET_BURROW_OFFSET * (1 - f);
          float voffset = 1 + Sinf(sin0 + (float) Math.PI / 2) * 0.5f;
          if (z > -50.0f) {
            TargetTailDraw(canvas, t.mX, t.mY, z + TARGET_BURROW_MAG * voffset);
          }

          z -= TARGET_BURROW_OFFSET;
          voffset = 1 + Sinf(sin0) * 0.5f;
          if (z > -50.0f) {
            TargetBodyDraw(canvas, t.mX, t.mY, z + TARGET_BURROW_MAG * voffset);
          }
          break;
        }

        case MUNCH: {
          // Spiral around the flower, then pause, then devour it.
          final float munchTime = GameTargetMunchTime();
          final float pauseTime = GameMunchPauseTime();
          final float SINK_TIME = 0.50f;
          final float RISE_TIME = munchTime - pauseTime
                                  - SINK_TIME;
          final float TARGET_MUNCH_FREQ = 16 * GameWalkSpeedFactor();
          final float WRIGGLE_MAG = 60.0f;
          final float RISE_HEIGHT_HEAD = 220.0f;
          final float RISE_HEIGHT_BODY = 300.0f;
          final float RISE_HEIGHT_TAIL = 380.0f;
          final float END_DEVOUR_HEIGHT = 80.0f;

          final float DELTA_TAIL = RISE_HEIGHT_TAIL - RISE_HEIGHT_HEAD;
          final float DELTA_BODY = RISE_HEIGHT_BODY - RISE_HEIGHT_HEAD;

          if (t.mTimer <= RISE_TIME) {
            // Wriggle while we rise.
            float f = MakeLerper(t.mTimer, 0, munchTime - 0.75f);
            float headF = Clamp(f + 0.20f, 0, 0.80f);
            float bodyF = Clamp(f + 0.10f, 0, 0.90f);
            float tailF = f;

            TargetTailDraw(canvas,
                           t.mX + Sinf(tailF * TARGET_MUNCH_FREQ) * WRIGGLE_MAG,
                           t.mY + Cosf(tailF * TARGET_MUNCH_FREQ) * WRIGGLE_MAG,
                           t.mZ + tailF * RISE_HEIGHT_TAIL);
            TargetBodyDraw(canvas,
                           t.mX + Sinf(bodyF * TARGET_MUNCH_FREQ) * WRIGGLE_MAG,
                           t.mY + Cosf(bodyF * TARGET_MUNCH_FREQ) * WRIGGLE_MAG,
                           t.mZ + bodyF * RISE_HEIGHT_TAIL);
            TargetHeadDraw(canvas,
                           t.mX + Sinf(headF * TARGET_MUNCH_FREQ) * WRIGGLE_MAG,
                           t.mY + Cosf(headF * TARGET_MUNCH_FREQ) * WRIGGLE_MAG,
                           t.mZ + headF * RISE_HEIGHT_TAIL);
          } else if (t.mTimer <= RISE_TIME + pauseTime) {
            // Just pause, waiting to munch!
            TargetTailDraw(canvas, t.mX, t.mY, t.mZ + RISE_HEIGHT_TAIL);
            TargetBodyDraw(canvas, t.mX, t.mY, t.mZ + RISE_HEIGHT_BODY);
            TargetHeadDraw(canvas, t.mX, t.mY, t.mZ + RISE_HEIGHT_HEAD);
          } else {
            // Devour!
            float f = MakeLerper(t.mTimer, munchTime,
                                 munchTime - SINK_TIME);
            TargetTailDraw(canvas, t.mX, t.mY,
                           t.mZ + Lerp(f, END_DEVOUR_HEIGHT, RISE_HEIGHT_HEAD) +
                           DELTA_TAIL);
            TargetBodyDraw(canvas, t.mX, t.mY,
                           t.mZ + Lerp(f, END_DEVOUR_HEIGHT, RISE_HEIGHT_HEAD) +
                           DELTA_BODY);
            TargetHeadDraw(canvas, t.mX, t.mY,
                           t.mZ + Lerp(f, END_DEVOUR_HEIGHT, RISE_HEIGHT_HEAD));
          }
          break;
        }

        case SHRIVEL: {
          float f = MakeLerper(t.mTimer, TARGET_SHRIVEL_TIME, 0);
          Coord c = Project(t.mX, t.mY, t.mZ + TARGET_RISE_HEAD_HEIGHT * f);
          AddCircle(c.x, c.y, t.mY, c.z * TARGET_HEAD_RADIUS * f, mLarvaPaint1);
          c = Project(t.mX, t.mY, t.mZ);
          AddCircle(c.x, c.y, t.mY, c.z * TARGET_BODY_RADIUS * f, mLarvaPaint2);
          c = Project(t.mX, t.mY, t.mZ - TARGET_RISE_HEAD_HEIGHT * f);
          AddCircle(c.x, c.y, t.mY, c.z * TARGET_TAIL_RADIUS * f, mLarvaPaint1);
          break;
        }

        case PUPATE: {
          // Show a little nubbin above ground.
          Coord c = Project(t.mX, t.mY, 0);
          AddCircle(c.x, c.y, t.mY, 10.0f * c.z, mBlackPaint);
          break;
        }

        case FLY_RISE:
        case FLY:
        case FLY_LAY_EGG:
        case FLY_ESCAPE: {
          float sin0 = Sinf(t.mTimer * TARGET_FLY_FREQ);
          float z = TARGET_FLY_HEIGHT;
          float dirx = t.mDirX;
          float diry = t.mDirY;
          if (t.mState == TargetState.FLY_RISE) {
            z = Lerp(MakeLerper(t.mTimer, 0, TARGET_FLY_RISE_TIME), -1, 1) * TARGET_FLY_HEIGHT;
            dirx = 0;
            diry = -1;
          }
          if (t.mState == TargetState.FLY_LAY_EGG) {
            dirx = 0;
            diry = -1;
          }

          z += TARGET_HEAD_FLY_HEIGHT;
          if (z > -50.0f) {
            TargetHeadDrawFly(canvas,
                              t.mX + dirx * TARGET_HEAD_FLY_OFFSET,
                              t.mY + diry * TARGET_HEAD_FLY_OFFSET,
                              z);
          }

          // body
          z -= TARGET_HEAD_FLY_HEIGHT;
          if (z > -50.0f) {
            TargetBodyDrawFly(canvas, t.mX, t.mY, z);
          }

          // wings
          // TODO

          // tail
          z += TARGET_TAIL_FLY_HEIGHT;
          if (z > -50.0f) {
            TargetTailDrawFly(canvas,
                              t.mX + dirx * TARGET_TAIL_FLY_OFFSET,
                              t.mY + diry * TARGET_TAIL_FLY_OFFSET,
                              z);
          }

          if (t.mState == TargetState.FLY_LAY_EGG) {
            float f = MakeLerper(
                t.mTimer, TARGET_LAY_EGG_TIME - 0.25f, TARGET_LAY_EGG_TIME);
            if (f > 0) {
              float h = Lerp(f, TARGET_FLY_HEIGHT + TARGET_TAIL_FLY_OFFSET - TARGET_TAIL_RADIUS, 0);
              Coord c = Project(t.mX, t.mY, h);
              AddCircle(c.x, c.y, t.mY, TARGET_EGG_RADIUS * c.z, mWhitePaint);
            }
          }
          
          break;
        }
      }
    }

    public static final float FLOWER_HEIGHT = 120.0f;
    public static final float FLOWER_LEAF_RADIUS = 40.0f;
    public static final float FLOWER_LEAF_OFFSET = 35.0f;
    public static final float FLOWER_CENTER_RADIUS = 30.0f;
    public static final float FLOWER_PETAL_RADIUS = 30.0f;
    
    private void FlowerDraw(Canvas canvas, Flower f) {
      float stemT = 0;
      if (f.mState == FlowerState.SEED) {
        // No stem.
      } else if (f.mState == FlowerState.SPROUT) {
        stemT = 0.1f;
      } else if (f.mState == FlowerState.GROW) {
        stemT = 0.1f + 0.9f * MakeLerper(f.mTimer, 0, FLOWER_GROW_TIME);
      } else {
        stemT = 1;
      }

      float leafT = MakeLerper(stemT, 0.5f, 1.0f);
      
      // Draw stem.
      float h = FLOWER_HEIGHT * stemT;
      Coord head = Project(f.mX, f.mY, f.mZ + h);
      if (stemT > 0) {
        Coord c0 = Project(f.mX, f.mY, f.mZ);
        canvas.drawLine(c0.x, c0.y, head.x, head.y, mFlowerStemPaint);
      }

      // Leaves.
      if (leafT > 0) {
        float off = FLOWER_LEAF_OFFSET * leafT;
        float r = FLOWER_LEAF_RADIUS * leafT;
        Coord c2 = Project(f.mX + off, f.mY, f.mZ + h / 2);
        Coord c3 = Project(f.mX - off, f.mY, f.mZ + h / 2);
        AddCircle(c2.x, c2.y, f.mY, r * c2.z, mFlowerStemPaint);
        AddCircle(c3.x, c3.y, f.mY, r * c3.z, mFlowerStemPaint);
      }

      // Bud.
      {
        float centerR = FLOWER_CENTER_RADIUS * stemT * head.z;
        AddCircle(head.x, head.y, f.mY, centerR, mFlowerStemPaint);
      }

      // Bloom.
      if (f.mState == FlowerState.BLOOM) {
        float t = MakeLerper(f.mTimer, 0, 0.5f);

        float centerR = FLOWER_CENTER_RADIUS * t * head.z;
        float offsetR = (FLOWER_PETAL_RADIUS + FLOWER_CENTER_RADIUS) * t * head.z;
        float petalR = FLOWER_PETAL_RADIUS * t * head.z;

        AddCircle(head.x, head.y, f.mY, centerR, mBlackPaint);
        AddCircle(head.x - offsetR, head.y, f.mY, petalR, mFlowerPetalPaint);
        AddCircle(head.x + offsetR, head.y, f.mY, petalR, mFlowerPetalPaint);
        AddCircle(head.x - offsetR * 0.5f, head.y - offsetR * 0.707f, f.mY, petalR, mFlowerPetalPaint);
        AddCircle(head.x + offsetR * 0.5f, head.y - offsetR * 0.707f, f.mY, petalR, mFlowerPetalPaint);
        AddCircle(head.x - offsetR * 0.5f, head.y + offsetR * 0.707f, f.mY, petalR, mFlowerPetalPaint);
        AddCircle(head.x + offsetR * 0.5f, head.y + offsetR * 0.707f, f.mY, petalR, mFlowerPetalPaint);
      }

      // Seed.
    }

    /**
     * Draws the game state to the provided canvas.
     */
    private void GameDraw(Canvas canvas) {
      if (mState == GameState.INVALID) {
        return;
      }

      CoordsClear();
      DisplayListClear();

      // Background.
      //canvas.drawRect(0, 0, mCanvasWidth, mCanvasHeight, mBlackPaint);
      canvas.drawRect(0, 0, mCanvasWidth, mHorizonHeight, mSkyPaint);
      canvas.drawRect(0, mHorizonHeight, mCanvasWidth, mCanvasHeight, mGroundPaint);

      // Debug: show playfield outline.
      if (false) {
        float f = MakeLerper(TARGET_MIN_Y, TARGET_MIN_CONE_Y, TARGET_MAX_Y);
        float x = Lerp(f, 0, mTargetMaxX);
        Coord c0 = Project(-x, TARGET_MIN_Y, 0);
        Coord c1 = Project(x, TARGET_MIN_Y, 0);
        Coord c2 = Project(mTargetMaxX, TARGET_MAX_Y, 0);
        Coord c3 = Project(-mTargetMaxX, TARGET_MAX_Y, 0);
        
        canvas.drawLine(c0.x, c0.y, c1.x, c1.y, mBlackPaint);
        canvas.drawLine(c1.x, c1.y, c2.x, c2.y, mBlackPaint);
        canvas.drawLine(c2.x, c2.y, c3.x, c3.y, mBlackPaint);
        canvas.drawLine(c3.x, c3.y, c0.x, c0.y, mBlackPaint);
      }

      // Draw the targets.
      for (int i = 0; i < mTargets.size(); i++) {
        Target t = mTargets.get(i);
        TargetDraw(canvas, t);
      }
      
      // Draw the flowers.
      for (int i = 0; i < mFlowers.size(); i++) {
        Flower f = mFlowers.get(i);
        FlowerDraw(canvas, f);
      }
      
      // Draw the shot shadows.
      for (int i = 0; i < mShots.size(); i++) {
        Shot s = mShots.get(i);
        Coord c = Project(s.mX, s.mY, 0);
        ShotDrawShadow(canvas, c.x, c.y, c.z, s.MAX_RADIUS);
      }
      
      FlingerDraw(canvas);

      // Draw the shots.
      for (int i = 0; i < mShots.size(); i++) {
        Shot s = mShots.get(i);
        Coord c = Project(s.mX, s.mY, s.mZ);
        ShotDraw(canvas, c.x, c.y, s.mY, c.z * s.MAX_RADIUS);
      }

      DisplayListDraw(canvas);

      if (mState == GameState.PLAYING) {
        if (mScorePhase == ScorePhase.SCORE_FLOWERS) {
          if (mScoreFlower >= 0 && mScoreFlower < mFlowers.size()) {
            Flower f = mFlowers.get(mScoreFlower);
            int amount = GameScoreFlowerAmount(mScoreFlower);
            if (f != null) {
              Coord c = Project(f.mX, f.mY, f.mZ + FLOWER_HEIGHT * 2.5f);
              String msg = String.format("%d", amount);
              float width = mScorePaint.measureText(msg, 0, msg.length());
              canvas.drawText(msg, 0, msg.length(), c.x - width / 2, c.y, mScorePaint);
            }
          }
        }
      }
    }

    class Coord {
      public float x;
      public float y;
      public float z;
    };

    private ArrayList<Coord> mCoords = new ArrayList<Coord>();
    private int mNextCoord = 0;

    private void CoordsClear() {
      mNextCoord = 0;
    }

    private Coord NextCoord() {
      Coord c;
      if (mNextCoord >= mCoords.size()) {
        c = new Coord();
        mCoords.add(c);
      } else {
        c = mCoords.get(mNextCoord);
      }
      mNextCoord++;
      return c;
    }

    private static final float YSCALE = 5.5f;
    private static final float YOFFSET = 120; // 40;
    private static final float ZSCALE = 100;
    private static final float ZOFFSET = 120;

    // Playfield x,y,z to screen x,y,z
    private Coord Project(float x, float y, float z) {
      Coord c = NextCoord();
      c.z = ZSCALE / (y + ZOFFSET);
      c.x = x * c.z + mCanvasWidth / 2;
      float H = mCanvasHeight + YOFFSET;
      c.y = (H - y * c.z * YSCALE) - z * c.z;
      return c;
    }

    // Screen x,y to playfield x,y,0
    private Coord Unproject(float x, float y) {
      Coord c = NextCoord();
      float H = mCanvasHeight + YOFFSET;
      float denom = (YSCALE * ZSCALE + y - H);
      if (denom <= 1) {
        return null;
      }
      c.y = ZOFFSET * (H - y) / denom;
      float one_over_z = (c.y + ZOFFSET) / ZSCALE;
      c.x = (x - mCanvasWidth / 2) * one_over_z;
      c.z = 1;
      return c;
    }

    private float Sinf(float f) {
      return (float) Math.sin(f);
    }
    
    private float Cosf(float f) {
      return (float) Math.cos(f);
    }
    
    private float Distance(float x0, float y0, float x1, float y1) {
      float dx = x1 - x0;
      float dy = y1 - y0;
      return (float) Math.sqrt(dx * dx + dy * dy);
    }

    private float Distance3(float x0, float y0, float z0, float x1, float y1, float z1) {
      float dx = x1 - x0;
      float dy = y1 - y0;
      float dz = z1 - z0;
      return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private void AddScore(int x) {
      if (mState != GameState.PLAYING) {
        return;
      }

      mScore += x;
      if (mScore < 0) {
        mScore = 0;
      }
      // Award a bonus flower every N points.
      final float BONUS_FLOWER_INTERVAL = 1000;
      while (mScore >= mLastBonusFlowerScore + BONUS_FLOWER_INTERVAL) {
        // Award a bonus flower.
        mLastBonusFlowerScore += BONUS_FLOWER_INTERVAL;
        FlowerCreate();
        // TODO: sounds etc.
      }

      ShowMessage("score", String.format(" %d", mScore));
    }

    private void SpringReset() {
      // Reset the spring.
      mHeadX = (mAnchor0X + mAnchor1X) / 2;
      mHeadY = (mAnchor0Y + mAnchor1Y) / 2;
      mSpringState = SPRING_RESTING;
      SpringComputeHeadLocation();
    }

    private void SpringHold(float x, float y) {
      Coord c = Project(mHeadX, mHeadY, 0);
      if (Distance(x, y, c.x, c.y) <= SPRING_GRAB_DISTANCE
          || Distance(x, y - SPRING_HOTSPOT_SETBACK, c.x, c.y)
          <= SPRING_GRAB_DISTANCE) {
        mSpringState = SPRING_HELD;
      }
    }

    private void FlingerMove(float sx, float sy) {
      sy = Math.max(mTouchHeight, sy);
      Coord c = Unproject(sx, sy - SPRING_HOTSPOT_SETBACK);
      if (c == null) {
        return;
      }
      if (mSpringState == SPRING_HELD) {
        mHeadX = c.x;
        mHeadY = c.y;
        SpringComputeHeadLocation();
      }
    }

    private void SpringComputeHeadLocation() {
      mBand0Length = Distance(mAnchor0X, mAnchor0Y, mHeadX, mHeadY);
      mBand1Length = Distance(mAnchor1X, mAnchor1Y, mHeadX, mHeadY);
    }

    private void SpringRelease() {
      if (mSpringState == SPRING_HELD) {
        if (Math.max(mBand0Length, mBand1Length) > BAND_RELEASE_LENGTH) {
          mSpringState = SPRING_RELEASED;
        } else {
          // Don't shoot.
          mSpringState = SPRING_RESTING;
        }
        // TODO: might be fun to actually track grabber motion &
        // compute vel.
        mHeadVelX = 0;
        mHeadVelY = 0;
      }
    }

    private void SpringUpdate(float dt) {
      switch (mSpringState) {
        case SPRING_HELD: {
          EffectStretch();
          break;
        }
        case SPRING_RESTING:
        case SPRING_RELEASED:
        case SPRING_EMITTED: {
          EffectStretch();

          float forceX = 0;
          float forceY = 0;

          // Two independent springs:
          if (mBand0Length > BAND_REST_LENGTH) {
            float force = (mBand0Length - BAND_REST_LENGTH) * BAND_K;
            float angle = (float)
                          Math.atan2(mAnchor0Y - mHeadY, mAnchor0X - mHeadX);
            forceX += Math.cos(angle) * force;
            forceY += Math.sin(angle) * force;
          }
          if (mBand1Length > BAND_REST_LENGTH) {
            float force = (mBand1Length - BAND_REST_LENGTH) * BAND_K;
            float angle = (float)
                          Math.atan2(mAnchor1Y - mHeadY, mAnchor1X - mHeadX);
            forceX += Math.cos(angle) * force;
            forceY += Math.sin(angle) * force;
          }

          float mass = BAND_LOADED_MASS;
          if (mSpringState == SPRING_EMITTED) {
            mass = BAND_UNLOADED_MASS;
          }
          float accelX = forceX / mass;
          float accelY = forceY / mass;

          float velX0 = mHeadVelX;
          float velY0 = mHeadVelY;
          mHeadVelX += accelX * dt;
          mHeadVelY += accelY * dt;

          // Some damping when we're not shooting.
          if (mSpringState != SPRING_RELEASED) {
            float c0 = (float) Math.exp((-1 / 0.1f) * dt);
            mHeadVelX = Clamp(-1000, mHeadVelX * c0, 1000);
            mHeadVelY = Clamp(-1000, mHeadVelY * c0, 1000);
          }

          mHeadX += (mHeadVelX + velX0) / 2 * dt;
          mHeadY += (mHeadVelY + velY0) / 2 * dt;

          float speed0 = Distance(0, 0, velX0, velY0);
          float speed1 = Distance(0, 0, mHeadVelX, mHeadVelY);
          
          SpringComputeHeadLocation();

          // Detect when to emit.
          if (mSpringState == SPRING_RELEASED) {
            if (speed1 < speed0) {
              // Emit the shot.
              mSpringState = SPRING_EMITTED;
              mReloadTime = 0;
              mShots.add(new Shot(mHeadX, mHeadY, 0,
                                  mHeadVelX, mHeadVelY, speed1 * SHOT_DZ_FACTOR));
            }
          }

          if (mSpringState == SPRING_EMITTED) {
            mReloadTime += dt;
            if (mReloadTime > SPRING_RELOAD_TIME) {
              mSpringState = SPRING_RESTING;
            }
          }

          break;
        }
      }
    }

    public void ShotUpdate(Shot s, float dt) {
      if (s.mAlive) {
        float y0 = s.mY;
        s.mX += dt * s.mDX;
        s.mY += dt * s.mDY;
        s.mZ += dt * s.mDZ;

        // Gravity.
        s.mDZ -= GRAVITY * dt;

        // Bounce off bottom
        if ((s.mY < 0 && s.mDY < 0)
            /* || (s.mY > mCanvasHeight && s.mDY > 0) */) {
          s.mDY = -s.mDY;
        }

        // Check for hits.
        for (int i = 0; i < mTargets.size(); i++) {
          Target t = mTargets.get(i);
          float r = TargetGetHitRadius(t);
          if (Distance3(t.mX, t.mY, 0, s.mX, s.mY, 0) <=
              TargetGetHitRadius(t)) {
            EffectHitTarget(false);
            t.mAlive = false;
            s.mAlive = false;
            AddScore(5);
            break;
          }
        }

        if (s.mZ < 0 && s.mAlive) {
          ShotLanded(s);
          s.mAlive = false;
        }
      }
    }

    private void ShotLanded(Shot s) {
      // TODO make a splash anim
      float x = s.mX;
      float y = s.mY;

      // TODO sound
      AddScore(-1);
    }

    private Coord TargetPickValidSpot() {
      for (;;) {
        float x = (float) (Math.random() * 2 - 1) * mTargetMaxX;
        float y = (float) Math.random() * (TARGET_MAX_Y - TARGET_MIN_Y) +
                  TARGET_MIN_Y;
        float xBound = ((y - TARGET_MIN_CONE_Y) / (TARGET_MAX_Y - TARGET_MIN_CONE_Y)) *
                       mTargetMaxX;
        if (x >= -xBound && x <= xBound) {
          Coord c = NextCoord();
          c.x = x;
          c.y = y;
          return c;
        }
      }
    }

    // Pick a random edible flower.
    // May return null.
    private Flower TargetFindEdibleFlower() {
      int index = (int) (Math.random() * mFlowers.size());
      for (int i = 0; i < mFlowers.size(); i++, index++) {
        Flower f = mFlowers.get(index % mFlowers.size());
        if (FlowerIsEdible(f)) {
          return f;
        }
      }
      return null;
    }
    
    private Target TargetCreate() {
      for (int i = 0; i < 100; i++) {
        Coord c = TargetPickValidSpot();
        float x = c.x;
        float y = c.y;

        // Is it too near any existing target?
        boolean ok = true;
        for (int j = 0; j < mTargets.size(); j++) {
          Target t = mTargets.get(j);
          if (Distance(t.mX, t.mY, x, y) < t.mMaxRadius * 2) {
            ok = false;
            break;
          }
        }
        if (ok) {
          // Valid location.
          // Pick a target location to move to.
          c = TargetPickValidSpot();
          float dx = 0; // (c.x - x) / (TARGET_MOVE_TIME - TARGET_EXPAND_TIME);
          float dy = 0; // (c.y - y) / (TARGET_MOVE_TIME - TARGET_EXPAND_TIME);
          Target t = new Target(x, y, dx, dy, TARGET_MAX_RADIUS);
          mTargets.add(t);
          return t;
        }
      }
      return null;
    }

    private float TargetGetHoleRadius(Target target) {
      float f = 0;
      switch (target.mState) {
        default:
          f = 0;
          break;
        case LOOK_OPEN:
          f = MakeLerper(target.mTimer, 0, TARGET_OPEN_TIME);
          break;
        case LOOK_PRE_RISE:
        case LOOK_RISE:
        case LOOK_DWELL_BEFORE_DROP:
        case LOOK_DROP:
        case BURROW:
          f = 1;
          break;
        case LOOK_CLOSE:
          f = MakeLerper(target.mTimer, TARGET_CLOSE_TIME, 0);
          break;
        case LARVA_DIE:
          f = MakeLerper(target.mTimer, TARGET_DIE_TIME, 0);
          break;

        case FLY_RISE:
          // TODO ramp
          f = 1;
          break;
      }
      f = Clamp(f, 0, 1);
      return TARGET_MAX_HOLE_RADIUS * f;
    }

    private float TargetGetHitRadius(Target target) {
      switch (target.mState) {
        default:
          return TARGET_MAX_RADIUS;

          // Cases when the target is not vulnerable.
        case EGG:
        case PUPATE:
        case SHRIVEL:
        case LOOK_OPEN:
        case LOOK_PRE_RISE:
        case LOOK_CLOSE:
        case LARVA_DIE:
          return 0;
      }
    }

    private static final float TARGET_EGG_TIME = 1.0f;
    private static final float TARGET_OPEN_TIME = 1.0f;
    private static final float TARGET_PRE_RISE_TIME = 0.500f;
    private static final float TARGET_RISE_TIME = 0.100f;
    private static final float TARGET_DWELL_TIME = 3;
    private static final float TARGET_DROP_TIME = 0.100f;
    private static final float TARGET_CLOSE_TIME = 1;
    private static final float TARGET_DIE_TIME = 0.250f;
    private static final float TARGET_MUNCH_TIME = 3.00f;
    private static final float TARGET_SHRIVEL_TIME = 1.0f;
    private static final float TARGET_BURROW_TIME = 1.0f;
    private static final float TARGET_PUPATE_TIME = 3.0f;
    private static final float TARGET_FLY_RISE_TIME = 1.0f;
    private static final float TARGET_LAY_EGG_TIME = 2.0f;

    private boolean TargetTimerCheck(Target t, float limit) {
      if (t.mTimer >= limit) {
        t.mTimer -= limit;
        return true;
      }
      return false;
    }

    private void TargetWalkTowardsFlower(Target t) {
      if (t.mWalkedCount > 2) {
        // Bug is old and hasn't eaten -- shrivel instead of walking.
        t.mState = TargetState.SHRIVEL;
        return;
      }
      
      // Decide where to walk to.
      Coord c;
      t.mFlowerToEat = TargetFindEdibleFlower();
      if (t.mFlowerToEat != null) {
        c = NextCoord();
        c.x = t.mFlowerToEat.mX;
        c.y = t.mFlowerToEat.mY;
        c.z = t.mFlowerToEat.mZ;
      } else {
        c = TargetPickValidSpot();
      }
      float dist = Distance(c.x, c.y, t.mX, t.mY);
      if (dist < 1) {
        t.mDX = (c.x - t.mX) / 1.0f;
        t.mDY = (c.x - t.mX) / 1.0f;
        t.mTravelTime = dist;
      } else {
        t.mDirX = (c.x - t.mX) / dist;
        t.mDirY = (c.y - t.mY) / dist;
        float speed = TARGET_WALK_SPEED * GameWalkSpeedFactor();
        t.mDX = t.mDirX * speed;
        t.mDY = t.mDirY * speed;
        t.mTravelTime = dist / speed;
      }
      t.mState = TargetState.WALK;
    }

    private void TargetFlyToLocation(Target t) {
      // Decide where to fly to.
      Coord c = TargetPickValidSpot();  // TODO: pick a flower location
      float dist = Distance(c.x, c.y, t.mX, t.mY);
      if (dist < 1) {
        t.mDX = (c.x - t.mX) / 1.0f;
        t.mDY = (c.x - t.mX) / 1.0f;
        t.mTravelTime = dist;
      } else {
        t.mDirX = (c.x - t.mX) / dist;
        t.mDirY = (c.y - t.mY) / dist;
        t.mDX = t.mDirX * TARGET_FLY_SPEED;
        t.mDY = t.mDirY * TARGET_FLY_SPEED;
        t.mTravelTime = dist / TARGET_FLY_SPEED;
      }
      t.mState = TargetState.FLY;
    }

    private int TargetMaxEggsToLay() {
      if (mLevel <= 2) {
        return 1;
      } else if (mLevel <= 4) {
        return 2;
      } else {
        int i = 2 + (mLevel - 4) / 2;
        if (i > 5) {
          i = 5;
        }
        return i;
      }
    }

    private void TargetUpdate(Target t, float dt) {
      t.mTimer += dt;
      switch (t.mState) {
        case EGG:
          if (TargetTimerCheck(t, TARGET_EGG_TIME)) {
            t.mState = TargetState.LOOK_OPEN;
          }
          break;
        case LOOK_OPEN:
          if (TargetTimerCheck(t, TARGET_OPEN_TIME)) {
            t.mState = TargetState.LOOK_PRE_RISE;
          }
          break;
        case LOOK_PRE_RISE:
          if (TargetTimerCheck(t, TARGET_PRE_RISE_TIME)) {
            t.mState = TargetState.LOOK_RISE;
          }
          break;
        case LOOK_RISE:
          if (TargetTimerCheck(t, TARGET_RISE_TIME)) {
            TargetWalkTowardsFlower(t);
          }
          break;
        case LOOK_DWELL_BEFORE_DROP:
          if (TargetTimerCheck(t, TARGET_DWELL_TIME)) {
            t.mState = TargetState.LOOK_DROP;
          }
          break;
        case LOOK_DROP:
          if (TargetTimerCheck(t, TARGET_DROP_TIME)) {
            t.mState = TargetState.LOOK_CLOSE;
          }
          break;
        case LOOK_CLOSE:
          if (TargetTimerCheck(t, TARGET_CLOSE_TIME)) {
            t.mAlive = false;
            // TODO sound/anim
            AddScore(-1);
          }
          break;
        case LARVA_DIE:
          // TODO
          break;

        case WALK:
          t.mX += t.mDX * dt;
          t.mY += t.mDY * dt;
          if (TargetTimerCheck(t, t.mTravelTime)) {
            // Done walking.
            t.mWalkedCount++;

            // Is the flower here?
            if (FlowerIsEdible(t.mFlowerToEat)) {
              t.mState = TargetState.MUNCH;
            } else {
              TargetWalkTowardsFlower(t);
            }
          }
          break;

        case MUNCH:
          if (TargetTimerCheck(t, GameTargetMunchTime())) {
            if (!t.mFlowerToEat.mAlive) {
              // We missed the flower.
              t.mState = TargetState.SHRIVEL;
            } else {
              // We just killed the flower!
              t.mFlowerToEat.mAlive = false;  // TODO effect
              t.mState = TargetState.BURROW;
            }
            t.mFlowerToEat = null;
          }
          break;

        case SHRIVEL:
          if (TargetTimerCheck(t, TARGET_SHRIVEL_TIME)) {
            // Done.
            t.mAlive = false;  // TODO effect
          }
          break;

        case BURROW:
          if (TargetTimerCheck(t, TARGET_BURROW_TIME)) {
            t.mState = TargetState.PUPATE;
          }
          break;

        case PUPATE:
          if (TargetTimerCheck(t, TARGET_PUPATE_TIME)) {
            t.mState = TargetState.FLY_RISE;
          }
          break;

        case FLY_RISE:
          if (TargetTimerCheck(t, TARGET_FLY_RISE_TIME)) {
            TargetFlyToLocation(t);
          }
          break;

        case FLY:
          t.mX += t.mDX * dt;
          t.mY += t.mDY * dt;

          if (TargetTimerCheck(t, t.mTravelTime)) {
            // Done flying.

            // Lay an egg!
            t.mDirX = 0;
            t.mDirY = -1;
            t.mState = TargetState.FLY_LAY_EGG;
          }
          break;

        case FLY_LAY_EGG:
          if (TargetTimerCheck(t, TARGET_LAY_EGG_TIME)) {
            mTargets.add(new Target(t.mX, t.mY, 0, 0, TARGET_MAX_RADIUS));
            t.mEggsLaid++;

            if (t.mEggsLaid < TargetMaxEggsToLay()) {
              TargetFlyToLocation(t);
            } else {
              // Escape!
              t.mDirX = 1;
              t.mDirY = 0;
              t.mDX = TARGET_FLY_SPEED;
              t.mDY = 0;
              if (t.mX < 0) {
                // Closer to the left edge.
                t.mDirX = -t.mDirX;
                t.mDX = -t.mDX;
              }
              t.mState = TargetState.FLY_ESCAPE;
            }
          }
          break;

        case FLY_ESCAPE: {
          t.mX += t.mDX * dt;
          t.mY += t.mDY * dt;
          Coord c = Project(t.mX, t.mY, 0);
          if (Math.abs(c.x - mCanvasWidth / 2) > mCanvasWidth / 2 + 200 * c.z + 50) {
            // Safely offscreen.
            t.mAlive = false;
          }
          break;
        }
      }
    }

    // Adds the new flower to mFlowers, and also returns it in case
    // you want to tweak it.
    private Flower FlowerCreate() {
      Coord c = TargetPickValidSpot();
      float x = c.x;
      float y = c.y;
      Flower f = new Flower(x, y);
      mFlowers.add(f);
      return f;
    }

    private boolean FlowerIsEdible(Flower f) {
      if (f != null && f.mAlive && f.mState == FlowerState.BLOOM) {
        return true;
      }
      return false;
    }

    private void FlingerDraw(Canvas canvas) {
      Coord a0 = Project(mAnchor0X, mAnchor0Y, 0);
      Coord a1 = Project(mAnchor1X, mAnchor1Y, 0);
      Coord h = Project(mHeadX, mHeadY, 0);

      // Pending shot shadow.
      if (mSpringState != SPRING_EMITTED) {
        ShotDrawShadow(canvas, h.x, h.y, h.z, Shot.MAX_RADIUS);
      }

      // Anchors.
      canvas.drawRect(a0.x - 5, a0.y - 10,
                      a0.x + 5, a0.y + 10, mAnchorPaint);
      canvas.drawRect(a1.x - 5, a1.y - 10,
                      a1.x + 5, a1.y + 10, mAnchorPaint);

      // Bands.
      float width =
        Clamp(BAND_MAX_WIDTH * (mAnchor1X - mAnchor0X) / (mBand0Length + mBand1Length),
              1, BAND_MAX_WIDTH);
      mBandPaint.setStrokeWidth(width);
      canvas.drawLine(a0.x, a0.y, h.x, h.y, mBandPaint);
      canvas.drawLine(a1.x, a1.y, h.x, h.y, mBandPaint);

      // Pending shot.
      if (mSpringState != SPRING_EMITTED) {
        ShotDraw(canvas, h.x, h.y, mHeadY, h.z * Shot.MAX_RADIUS);
      }
    }

    private void EffectBounce(float x, float y, float z) {
      mVibrator.vibrate(10);  // Very slight tick
      // TODO sound
    }

    private void EffectHitTarget(boolean bounced) {
      if (bounced) {
        mVibrator.vibrate(20);
        // TODO sound
      } else {
        mVibrator.vibrate(30);
        // TODO sound
      }
    }

    private void EffectStretch() {
      float stretch = Math.max(0, (mBand0Length - BAND_REST_LENGTH)) +
                      Math.max(0, (mBand1Length - BAND_REST_LENGTH));
      int s1 = (int) (stretch / 10);
      int s0 = (int) (mStretchEffectLength / 10);
      mStretchEffectLength = stretch;

      if (s1 != s0) {
        mVibrator.vibrate(5);
      }
    }

    private boolean FlowerTimerCheck(Flower f, float limit) {
      if (f.mTimer >= limit) {
        f.mTimer -= limit;
        return true;
      }
      return false;
    }

    private static final float FLOWER_SPROUT_TIME = 0.5f;
    private static final float FLOWER_GROW_TIME = 0.5f;
    private static final float FLOWER_BUD_TIME = 0.5f;
    private static final float FLOWER_BLOOM_TIME = 1.0f;
    private static final float FLOWER_SEED_TIME = 1.0f;
    
    private void FlowerUpdate(Flower f, float dt) {
      f.mTimer += dt;
      switch (f.mState) {
        case SPROUT:
          if (FlowerTimerCheck(f, FLOWER_SPROUT_TIME)) {
            f.mState = FlowerState.GROW;
          }
          break;
        case GROW:
          if (FlowerTimerCheck(f, FLOWER_GROW_TIME)) {
            f.mState = FlowerState.BUD;
          }
          break;
        case BUD:
          if (FlowerTimerCheck(f, FLOWER_BUD_TIME)) {
            f.mState = FlowerState.BLOOM;
          }
          break;
        case BLOOM:
          // Bloom indefinitely.

          // TODO: add interstitial phase, where we burst & spawn seeds!
          
          // if (FlowerTimerCheck(f, FLOWER_BLOOM_TIME)) {
          //   f.mState = FlowerState.SEED;

          //   // TODO: burst & spawn seeds!
          //   f.mAlive = false;
          // }
          break;
        case SEED:
          if (FlowerTimerCheck(f, FLOWER_SEED_TIME)) {
            f.mState = FlowerState.SPROUT;
          }
          break;
      }
    }

    private void FlowersUpdate(float dt) {
      for (int i = 0; i < mFlowers.size(); i++) {
        FlowerUpdate(mFlowers.get(i), dt);
      }
    }

    private int GameEggsToAdd() {
      if (mLevel <= 2) {
        return 4;
      } else if (mLevel <= 4) {
        return 5;
      } else {
        return 6 + (int) (mLevel - 5);
      }
    }

    private float GameMunchPauseTime() {
      if (mLevel <= 4) {
        return 0.75f;
      } else if (mLevel <= 8) {
        return 0.50f;
      } else {
        return 0.25f;
      }
    }

    private int GameScoreFlowerAmount(int flower) {
      if (flower == 0) {
        return 25;
      } else if (flower == 1) {
        return 50;
      } else if (flower == 2) {
        return 100;
      } else {
        return 200;
      }
    }

    private float GameWalkSpeedFactor() {
      final float[] speeds = new float[] {
        0.50f,
        0.75f,
        0.90f,
        1.00f
      };
      if (mLevel < speeds.length) {
        return speeds[mLevel];
      } else {
        return speeds[speeds.length - 1];
      }
    }

    private float GameTargetMunchTime() {
      return TARGET_MUNCH_TIME / GameWalkSpeedFactor();
    }

    private void TargetsUpdate(float dt) {
      // Update targets.
      for (int i = 0; i < mTargets.size(); i++) {
        TargetUpdate(mTargets.get(i), dt);
      }
      for (int i = mTargets.size() - 1; i >= 0; i--) {
        if (mTargets.get(i).mAlive == false) {
          mTargets.remove(i);
        }
      }
      for (int i = mFlowers.size() - 1; i >= 0; i--) {
        if (mFlowers.get(i).mAlive == false) {
          mFlowers.remove(i);
        }
      }
    }

    private void ShotsUpdate(float dt) {
      // Update shots.
      for (int i = 0; i < mShots.size(); i++) {
        ShotUpdate(mShots.get(i), dt);
      }
      for (int i = mShots.size() - 1; i >= 0; i--) {
        if (mShots.get(i).mAlive == false) {
          mShots.remove(i);
        }
      }
    }

    private void GameStateUpdate(float dt) {
      if (mState == GameState.PLAYING) {
        if (mFlowers.size() == 0) {
          GameSetState(GameState.GAME_OVER);
        }
        if (mTargets.size() == 0) {
          if (mScorePhase == ScorePhase.NONE) {
            mScorePhase = ScorePhase.MESSAGE;
            mGameTimer = 0;
            SetMessage("Nice Job!");
          }
        }

        switch (mScorePhase) {
          default:
          case NONE:
            break;

          case MESSAGE:
            if (mGameTimer >= 2.0f) {
              mGameTimer -= 2.0f;
              mScorePhase = ScorePhase.SCORE_FLOWERS;
              SetMessage("");
              mScoreFlower = -1;
            }
            break;

          case SCORE_FLOWERS:
            if (mGameTimer >= 0.5f) {
              mGameTimer -= 0.5f;
              mScoreFlower++;
              if (mScoreFlower < mFlowers.size()) {
                AddScore(GameScoreFlowerAmount(mScoreFlower));
                // TODO effects
              } else {
                // Done scoring the flowers.
                mScorePhase = ScorePhase.MOVE_FLOWERS;
                mGameTimer = 0;
                mFlowerToMove = 0;
              }
            }
            break;

          case MOVE_FLOWERS:
            if (mGameTimer >= 0.5f) {
              mGameTimer -= 0.5f;

              if (mFlowerToMove < mFlowers.size()) {
                // Replace this flower with a new one somewhere else.
                Coord c = TargetPickValidSpot();
                mFlowers.set(mFlowerToMove, new Flower(c.x, c.y));
                mFlowerToMove++;
              } else {
                // Done moving flowers.
                mScorePhase = ScorePhase.ADD_EGGS;
                mEggsToAdd = GameEggsToAdd();
              }
            }
            break;

          case ADD_EGGS:
            if (mGameTimer >= 0.5f) {
              mGameTimer -= 0.5f;

              if (mEggsToAdd > 0) {
                Target t = TargetCreate();
                // Delay the hatching of this egg, so the flowers can
                // finish growing.
                t.mTimer -= EGG_PREDELAY;
                // TODO effects
                mEggsToAdd--;
              } else {
                // Done adding eggs... now go to it!
                mLevel++;
                mScorePhase = ScorePhase.NONE;
              }
            }
            break;
        }
                
      } else {
        if (mTargets.size() == 0) {
          // Make sure the attract mode goes into a non-power-sucking
          // state after not too long.
          Pause();
        }
      }

      if (mState == GameState.GAME_OVER && mGameTimer > 15.0f) {
        GameSetState(GameState.ATTRACT);
      }
    }

    private void GameUpdate() {
      if (mState == GameState.INVALID) {
        if (mTargetMaxX > 100) {
          GameSetState(GameState.ATTRACT);
        } else {
          return;
        }
      }
      
      long now = System.currentTimeMillis();

      // Do nothing if mLastTime is in the future.
      // This allows the game-start to delay the start of the physics
      // by 100ms or whatever.
      if (mLastTime >= now) return;

      float deltaTime = (now - mLastTime) / 1000.0f;
      mLastTime = now;

      // If deltaTime is very large, allow the sim to slow down,
      // instead of making lots of sub-ticks.
      deltaTime = Clamp(deltaTime, 0, 0.200f);

      // Enforce a maximum tick size to keep the physics reliable.
      // Use multiple subticks if the deltaTime is too large.
      while (deltaTime > 0) {
        float dt = Clamp(deltaTime, 0, 0.017f);
        deltaTime -= dt;
      
        mGameTimer += dt;
        SpringUpdate(dt);
        FlowersUpdate(dt);
        TargetsUpdate(dt);
        ShotsUpdate(dt);
        GameStateUpdate(dt);
      }
    }
  }  // class FlingersThread

  /** Handle to the application context, used to e.g. fetch Drawables. */
  private Context mContext;

  /** Pointer to the text view to display "Paused.." etc. */
  private TextView mStatusText;
  private TextView mScoreText;

  /** The thread that actually draws the animation */
  private FlingersThread thread;

  public FlingersView(Context context, AttributeSet attrs) {
    super(context, attrs);

    // register our interest in hearing about changes to our surface
    SurfaceHolder holder = getHolder();
    holder.addCallback(this);

    // create thread only; it's started in surfaceCreated()
    thread = new FlingersThread(holder, context, new Handler() {
        @Override
        public void handleMessage(Message m) {
          mStatusText.setVisibility(m.getData().getInt("viz"));
          mStatusText.setText(m.getData().getString("text"));
        }},
        new Handler() {
          @Override
          public void handleMessage(Message m) {
            mScoreText.setVisibility(m.getData().getInt("viz"));
            mScoreText.setText(m.getData().getString("text"));
          }
        }
      );

    setFocusable(true); // make sure we get key events
  }

  /**
   * Fetches the animation thread corresponding to this FlingersView.
   * 
   * @return the animation thread
   */
  public FlingersThread getThread() {
    return thread;
  }

  @Override
  public boolean onTouchEvent(MotionEvent event) {
    return thread.doTouch(event);
  }

  /**
   * Standard override to get key-press events.
   */
  @Override
  public boolean onKeyDown(int keyCode, KeyEvent msg) {
    return thread.doKeyDown(keyCode, msg);
  }

  /**
   * Standard override for key-up. We actually care about these, so we can
   * turn off the engine or stop rotating.
   */
  @Override
  public boolean onKeyUp(int keyCode, KeyEvent msg) {
    return thread.doKeyUp(keyCode, msg);
  }

  /**
   * Standard window-focus override. Notice focus lost so we can pause on
   * focus lost. e.g. user switches to take a call.
   */
  @Override
  public void onWindowFocusChanged(boolean hasWindowFocus) {
    if (!hasWindowFocus) thread.Pause();
  }

  /**
   * Installs a pointer to the text view used for messages.
   */
  public void setTextView(TextView textView) {
    mStatusText = textView;
  }

  public void setScoreTextView(TextView textView) {
    mScoreText = textView;
  }

  /* Callback invoked when the surface dimensions change. */
  public void surfaceChanged(SurfaceHolder holder, int format, int width,
                             int height) {
    thread.setSurfaceSize(width, height);
  }

  /*
   * Callback invoked when the Surface has been created and is ready to be
   * used.
   */
  public void surfaceCreated(SurfaceHolder holder) {
    // start the thread here so that we don't busy-wait in run()
    // waiting for the surface to be created
    thread.setRunning(true);
    thread.start();
  }

  /*
   * Callback invoked when the Surface has been destroyed and must no longer
   * be touched. WARNING: after this method returns, the Surface/Canvas must
   * never be touched again!
   */
  public void surfaceDestroyed(SurfaceHolder holder) {
    // we have to tell thread to shut down & wait for it to finish, or else
    // it might touch the Surface after we return and explode
    boolean retry = true;
    thread.setRunning(false);
    while (retry) {
      try {
        thread.join();
        retry = false;
      } catch (InterruptedException e) {
      }
    }
  }
}
