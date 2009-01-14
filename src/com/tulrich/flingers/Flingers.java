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

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.Window;
import android.widget.TextView;

import com.tulrich.flingers.FlingersView.FlingersThread;
import com.tulrich.flingers.FlingersView;
import com.tulrich.flingers.R;

/**
 * This is a simple Flingers activity that houses a single FlingersView. It
 * demonstrates...
 * <ul>
 * <li>animating by calling invalidate() from draw()
 * <li>loading and drawing resources
 * <li>handling onPause() in an animation
 * </ul>
 *
 * @author tu@tulrich.com (Thatcher Ulrich)
 * Originally derived from LunarLander android sample.
 */
public class Flingers extends Activity {
  private static final int MENU_PAUSE = 1;
  private static final int MENU_RESUME = 2;
  private static final int MENU_START = 3;
  private static final int MENU_STOP = 4;
  private static final int MENU_ABOUT = 5;

  /** A handle to the thread that's actually running the animation. */
  private FlingersThread mFlingersThread;

  /** A handle to the View in which the game is running. */
  private FlingersView mFlingersView;

  /**
   * Invoked during init to give the Activity a chance to set up its Menu.
   * 
   * @param menu the Menu to which entries may be added
   * @return true
   */
  @Override
  public boolean onCreateOptionsMenu(Menu menu) {
    super.onCreateOptionsMenu(menu);

    menu.add(0, MENU_START, 0, R.string.menu_start);
    menu.add(0, MENU_STOP, 0, R.string.menu_stop);
    menu.add(0, MENU_PAUSE, 0, R.string.menu_pause);
    menu.add(0, MENU_RESUME, 0, R.string.menu_resume);
    menu.add(0, MENU_ABOUT, 0, R.string.menu_about);

    return true;
  }

  /**
   * Invoked when the user selects an item from the Menu.
   * 
   * @param item the Menu entry which was selected
   * @return true if the Menu item was legit (and we consumed it), false
   *         otherwise
   */
  @Override
  public boolean onOptionsItemSelected(MenuItem item) {
    switch (item.getItemId()) {
      case MENU_START:
        mFlingersThread.GameSetState(FlingersView.GameState.PLAYING);
        return true;
      case MENU_STOP:
        mFlingersThread.GameSetState(FlingersView.GameState.ATTRACT);
        return true;
      case MENU_PAUSE:
        mFlingersThread.Pause();
        return true;
      case MENU_RESUME:
        mFlingersThread.Unpause();
        return true;
      case MENU_ABOUT:
        mFlingersThread.About();
        return true;
    }

    return false;
  }

  /**
   * Invoked when the Activity is created.
   * 
   * @param savedInstanceState a Bundle containing state saved from a previous
   *        execution, or null if this is a new execution
   */
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    // turn off the window's title bar
    requestWindowFeature(Window.FEATURE_NO_TITLE);

    // tell system to use the layout defined in our XML file
    setContentView(R.layout.flingers_layout);

    // get handles to the FlingersView from XML, and its FlingersThread
    mFlingersView = (FlingersView) findViewById(R.id.flingers);
    mFlingersThread = mFlingersView.getThread();

    // give the FlingersView a handle to the TextView used for messages
    mFlingersView.setTextView((TextView) findViewById(R.id.text));
    mFlingersView.setScoreTextView((TextView) findViewById(R.id.score));

    if (savedInstanceState == null) {
      // we were just launched: set up a new game
      mFlingersThread.GameSetState(FlingersView.GameState.INVALID);
      Log.w(this.getClass().getName(), "SIS is null");
    } else {
      // we are being restored: resume a previous game
      mFlingersThread.restoreState(savedInstanceState);
      Log.w(this.getClass().getName(), "SIS is nonnull");
    }
  }

  /**
   * Invoked when the Activity loses user focus.
   */
  @Override
  protected void onPause() {
    super.onPause();
    mFlingersView.getThread().Pause(); // pause game when Activity pauses
  }

  /**
   * Notification that something is about to happen, to give the Activity a
   * chance to save state.
   * 
   * @param outState a Bundle into which this Activity should save its state
   */
  @Override
  protected void onSaveInstanceState(Bundle outState) {
    // just have the View's thread save its state into our Bundle
    super.onSaveInstanceState(outState);
    mFlingersThread.saveState(outState);
    Log.w(this.getClass().getName(), "SIS called");
  }
}
