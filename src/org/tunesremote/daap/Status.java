/*
    TunesRemote+ - http://code.google.com/p/tunesremote-plus/
    
    Copyright (C) 2008 Jeffrey Sharkey, http://jsharkey.org/
    Copyright (C) 2010 TunesRemote+, http://code.google.com/p/tunesremote-plus/
    
    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
    
    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
    
    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
    
    The Initial Developer of the Original Code is Jeffrey Sharkey.
    Portions created by Jeffrey Sharkey are
    Copyright (C) 2008. Jeffrey Sharkey, http://jsharkey.org/
    All Rights Reserved.
 */

package org.tunesremote.daap;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.tunesremote.util.ThreadExecutor;

import android.graphics.Bitmap;
import android.os.Handler;
import android.util.Log;

/**
 * Status handles status information, including background timer thread also
 * subscribes to keep-alive event updates.
 * <p>
 */
public class Status {

   public final static String TAG = Status.class.toString();

   /**
    * Constants
    */
   public final static int REPEAT_OFF = 0;
   public final static int REPEAT_SINGLE = 1;
   public final static int REPEAT_ALL = 2;
   public final static int SHUFFLE_OFF = 0;
   public final static int SHUFFLE_ON = 1;
   public final static int STATE_PAUSED = 3;
   public final static int STATE_PLAYING = 4;
   public final static int UPDATE_PROGRESS = 2;
   public final static int UPDATE_STATE = 3;
   public final static int UPDATE_TRACK = 4;
   public final static int UPDATE_COVER = 5;
   public final static int UPDATE_RATING = 6;
   private final static int MAX_FAILURES = 10;

   /**
    * Fields
    */
   public boolean coverEmpty = true;
   public Bitmap coverCache = null;
   public String albumId = "";
   protected int repeatStatus = REPEAT_OFF, shuffleStatus = SHUFFLE_OFF, playStatus = STATE_PAUSED;
   protected final AtomicBoolean destroyThread = new AtomicBoolean(false);
   private long rating = -1;
   private long songId = 0;
   private String trackName = "", trackArtist = "", trackAlbum = "";
   private long progressTotal = 0, progressRemain = 0;
   private final Session session;
   private Handler update = null;
   private static AtomicInteger failures = new AtomicInteger(0);
   private long revision = 1;

   /**
    * Constructor accepts a Session and UI Handler to update the UI.
    * @param session
    * @param update
    */
   public Status(Session session, Handler update) {
      this.session = session;
      this.update = update;

      // create two threads, one for backend keep-alive updates
      // and a second one to update running time and fire gui events

      this.progress.start();
      this.keepalive.start();

      // keep our status updated with server however we need to
      // end thread when getting any 404 responses, etc
   }

   public void updateHandler(Handler handler) {
      this.update = handler;
   }

   protected final Thread progress = new Thread(new Runnable() {
      public void run() {
         while (true) {
            // when in playing state, keep moving progress forward
            while (playStatus == STATE_PLAYING) {
               // Log.d(TAG, "thread entering playing loop");
               if (destroyThread.get())
                  break;
               long anchor = System.currentTimeMillis();
               try {
                  Thread.sleep(1000);
               } catch (InterruptedException e) {
                  // someone jolted us awake during playing, which means track
                  // changed
                  Log.d(TAG, "someone jolted us during STATE_PLAYING loop");
                  continue;
               }

               if (destroyThread.get())
                  break;

               // update progress and gui
               progressRemain -= (System.currentTimeMillis() - anchor);
               if (update != null)
                  update.sendEmptyMessage(UPDATE_PROGRESS);

               // trigger a forced update if we seem to gone past end of song
               if (progressRemain <= 0) {
                  Log.d(TAG, "suggesting that we fetch new song");
                  fetchUpdate();
               }
            }

            // keep sleeping while in paused state
            while (playStatus == STATE_PAUSED) {
               Log.d(TAG, "thread entering paused loop");
               try {
                  Thread.sleep(10000);
               } catch (InterruptedException e) {
                  // someone probably jolted us awake with a status update
                  Log.d(TAG, "someone jolted us during STATE_PAUSED loop");
               }

               if (destroyThread.get())
                  break;
            }

            // one final sleep to make sure we behave nicely in case of unknown
            // status
            try {
               Thread.sleep(1000);
            } catch (InterruptedException e) {
               Log.d(TAG, "someone jolted us during OVERALL loop");
            }

            if (destroyThread.get())
               break;
         }
         Log.w(TAG, "Status Progress Thread Killed!");
      }
   });

   protected final Thread keepalive = new Thread(new Runnable() {
      public void run() {
         while (true) {
            try {
               // sleep a few seconds to make sure we dont kill stuff
               Thread.sleep(1000);
               if (destroyThread.get())
                  break;

               // try fetching next revision update using socket keepalive
               // approach
               // using the next revision-number will make itunes keepalive
               // until something happens
               // http://192.168.254.128:3689/ctrl-int/1/playstatusupdate?revision-number=1&session-id=1034286700
               parseUpdate(RequestHelper.requestParsed(
                        String.format("%s/ctrl-int/1/playstatusupdate?revision-number=%d&session-id=%s",
                                 session.getRequestBase(), revision, session.sessionId), true));
            } catch (Exception e) {
               Log.d(TAG, String.format("Exception in keepalive thread, so killing try# %d", failures.get()), e);
               if (failures.incrementAndGet() > MAX_FAILURES)
                  destroy();
            }
         }
         Log.w(TAG, "Status KeepAlive Thread Killed!");
      }
   });

   public void destroy() {
      // destroy our internal thread
      Log.w(TAG, "trying to destroy internal status thread");
      if (this.destroyThread.get())
         return;
      this.destroyThread.set(true);
      this.progress.interrupt();
      this.keepalive.interrupt();
   }

   public void fetchUpdate() {
      Log.d(TAG, "Fetching Update From Server...");
      // force a status update, will pass along to parseUpdate()
      ThreadExecutor.runTask(new Runnable() {
         public void run() {
            try {
               // using revision-number=1 will make sure we return instantly
               // http://192.168.254.128:3689/ctrl-int/1/playstatusupdate?revision-number=1&session-id=1034286700
               parseUpdate(RequestHelper.requestParsed(
                        String.format("%s/ctrl-int/1/playstatusupdate?revision-number=%d&session-id=%s",
                                 session.getRequestBase(), 1, session.sessionId), false));
            } catch (Exception e) {
               Log.w(TAG, e);
               if (failures.incrementAndGet() > MAX_FAILURES)
                  destroy();
            }
         }
      });

   }

   protected void parseUpdate(Response resp) throws Exception {
      // keep track of the worst update that could happen
      int updateType = UPDATE_PROGRESS;

      resp = resp.getNested("cmst");
      this.revision = resp.getNumberLong("cmsr");
      extractSongId(resp.getRaw("canp"));

      int playStatus = (int) resp.getNumberLong("caps");
      int shuffleStatus = (int) resp.getNumberLong("cash");
      int repeatStatus = (int) resp.getNumberLong("carp");

      // update state if changed
      if (playStatus != this.playStatus || shuffleStatus != this.shuffleStatus || repeatStatus != this.repeatStatus) {
         updateType = UPDATE_STATE;
         this.playStatus = playStatus;
         this.shuffleStatus = shuffleStatus;
         this.repeatStatus = repeatStatus;

         Log.d(TAG, "about to interrupt #1");
         this.progress.interrupt();
      }

      final String trackName = resp.getString("cann");
      final String trackArtist = resp.getString("cana");
      final String trackAlbum = resp.getString("canl");

      this.albumId = resp.getNumberString("asai");

      // update if track changed
      if (!trackName.equals(this.trackName) || !trackArtist.equals(this.trackArtist)
               || !trackAlbum.equals(this.trackAlbum)) {
         updateType = UPDATE_TRACK;
         this.trackName = trackName;
         this.trackArtist = trackArtist;
         this.trackAlbum = trackAlbum;

         // clear any coverart cache
         this.coverCache = null;
         this.fetchCover();

         // clear rating
         this.rating = -1;
         this.fetchRating();

         // tell our progress updating thread about a new track
         // this makes sure he doesnt count progress from last song against this
         // new one
         Log.d(TAG, "about to interrupt #2");
         this.progress.interrupt();
      }

      this.progressRemain = resp.getNumberLong("cant");
      this.progressTotal = resp.getNumberLong("cast");

      // send off updated event to gui
      if (update != null)
         this.update.sendEmptyMessage(updateType);
   }

   public void fetchCover() {
      if (coverCache == null) {
         // spawn thread to fetch coverart
         ThreadExecutor.runTask(new Runnable() {
            public void run() {
               try {
                  // http://192.168.254.128:3689/ctrl-int/1/nowplayingartwork?mw=320&mh=320&session-id=1940361390
                  coverCache = RequestHelper.requestBitmap(String.format(
                           "%s/ctrl-int/1/nowplayingartwork?mw=320&mh=320&session-id=%s", session.getRequestBase(),
                           session.sessionId));
               } catch (Exception e) {
                  e.printStackTrace();
               }
               coverEmpty = (coverCache == null);
               if (update != null)
                  update.sendEmptyMessage(UPDATE_COVER);
            }
         });
      }
   }

   private void extractSongId(byte[] bs) {
      songId = 0;

      // This is a PITA in Java....
      songId = (bs[12] & 0xff) << 24;
      songId |= (bs[13] & 0xff) << 16;
      songId |= (bs[14] & 0xff) << 8;
      songId |= bs[15] & 0xff;

   }

   public void fetchRating() {
      // spawn thread to fetch rating
      ThreadExecutor.runTask(new Runnable() {
         public void run() {
            try {
               Response resp = RequestHelper.requestParsed(
                        String.format(
                                 "%s/databases/%d/items?session-id=%s&meta=dacp.userrating,daap.userrating&type=music&query='dmap.itemid:%d'",
                                 session.getRequestBase(), session.databaseId, session.sessionId, songId), false);

               if (update != null) {
                  rating = resp.getNested("apso").getNested("mlcl").getNested("mlit").getNumberLong("asur");
                  update.sendEmptyMessage(UPDATE_RATING);
               }

            } catch (Exception e) {
               e.printStackTrace();
            }
         }
      });
   }

   public long getVolume() {
      try {
         // http://192.168.254.128:3689/ctrl-int/1/getproperty?properties=dmcp.volume&session-id=130883770
         Response resp = RequestHelper.requestParsed(
                  String.format("%s/ctrl-int/1/getproperty?properties=dmcp.volume&session-id=%s",
                           session.getRequestBase(), session.sessionId), false);
         return resp.getNested("cmgt").getNumberLong("cmvo");
      } catch (Exception e) {
         e.printStackTrace();
      }
      return -1;

      /*
       * cmgt --+ mstt 4 000000c8 == 200 cmvo 4 00000054 == 84
       */
   }

   public int getProgress() {
      return (int) ((this.progressTotal - this.progressRemain) / 1000);
   }

   public int getRemaining() {
      return (int) (this.progressRemain / 1000);
   }

   public int getProgressTotal() {
      return (int) (this.progressTotal / 1000);
   }

   public int getShuffle() {
      return this.shuffleStatus;
   }

   public int getRepeat() {
      return this.repeatStatus;
   }

   public int getPlayStatus() {
      return this.playStatus;
   }

   public String getTrackName() {
      return this.trackName;
   }

   public String getTrackArtist() {
      return this.trackArtist;
   }

   public String getTrackAlbum() {
      return this.trackAlbum;
   }

   public long getRating() {
      return this.rating;
   }

   public String getAlbumId() {
      return this.albumId;
   }
}
