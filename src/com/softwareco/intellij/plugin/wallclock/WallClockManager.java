package com.softwareco.intellij.plugin.wallclock;


import com.softwareco.intellij.plugin.AsyncManager;
import com.softwareco.intellij.plugin.SoftwareCoSessionManager;
import com.softwareco.intellij.plugin.SoftwareCoUtils;
import com.softwareco.intellij.plugin.models.TimeData;
import com.softwareco.intellij.plugin.timedata.TimeDataManager;
import com.softwareco.intellij.plugin.tree.CodeTimeToolWindow;

import java.util.logging.Logger;

public class WallClockManager {

    public static final Logger log = Logger.getLogger("SoftwareCoMusicManager");

    private static final int SECONDS_INCREMENT = 30;

    private static WallClockManager instance = null;
    private AsyncManager asyncManager = AsyncManager.getInstance();

    public static WallClockManager getInstance() {
        if (instance == null) {
            instance = new WallClockManager();
        }
        return instance;
    }

    private WallClockManager() {
        // initialize the timer
        this.init();
    }

    private void init() {
        final Runnable wallClockTimer = () -> updateWallClockTime();
        asyncManager.scheduleService(
                wallClockTimer, "wallClockTimer", 0, SECONDS_INCREMENT);
    }

    private static void updateWallClockTime() {
        long wctime = getWcTimeInSeconds() + SECONDS_INCREMENT;
        SoftwareCoSessionManager.setNumericItem("wctime", wctime);
        dispatchStatusViewUpdate();

        // update the json time data file
        updateTimeData();
    }

    public static void dispatchStatusViewUpdate() {
        long wcTimeVal = getWcTimeInSeconds();

        String icon = SoftwareCoUtils.showingStatusText() ? "software-paw.png" : "clock-blue.png";

        long minutes = wcTimeVal / 60;
        String currentDayTimeStr = SoftwareCoUtils.humanizeMinutes(minutes);
        SoftwareCoUtils.updateStatusBar(
                icon, currentDayTimeStr, "Code time today vs. your daily average. Click to see more from Code Time");
        // refresh the code time tree view
        CodeTimeToolWindow.refresh();
    }

    public static long getWcTimeInSeconds() {
        return SoftwareCoSessionManager.getNumericItem("wctime", 0L);
    }

    public static void setWcTime(long seconds) {
        SoftwareCoSessionManager.setNumericItem("wctime", seconds);
        updateWallClockTime();
    }

    private static void updateTimeData() {
        long editorSeconds = getWcTimeInSeconds();
        TimeData td = TimeDataManager.getTodayTimeDataSummary();

        TimeDataManager.updateTimeDataSummary(editorSeconds, td.getSession_seconds(), td.getFile_seconds());
    }

    public static void updateBasedOnSessionSeconds(long sessionSeconds) {
        long wcTimeVal = getWcTimeInSeconds();
        if (wcTimeVal < sessionSeconds) {
            // this will update the status bar and tree view metrics
            setWcTime((sessionSeconds));
        } else {
            // just update the tree view metrics
            CodeTimeToolWindow.refresh();
        }
    }
}