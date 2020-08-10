package com.softwareco.intellij.plugin;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.softwareco.intellij.plugin.managers.EventTrackerManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Logger;
import java.util.stream.Stream;


public class SoftwareCoEventManager {

    public static final Logger LOG = Logger.getLogger("SoftwareCoEventManager");

    private static SoftwareCoEventManager instance = null;

    private EventTrackerManager tracker;
    private KeystrokeManager keystrokeMgr;
    private SoftwareCoSessionManager sessionMgr;

    public static SoftwareCoEventManager getInstance() {
        if (instance == null) {
            instance = new SoftwareCoEventManager();
        }
        return instance;
    }

    private SoftwareCoEventManager() {
        keystrokeMgr = KeystrokeManager.getInstance();
        sessionMgr = SoftwareCoSessionManager.getInstance();
        tracker = EventTrackerManager.getInstance();
    }

    private KeystrokeCount getCurrentKeystrokeCount(String projectName, String projectDir) {
        KeystrokeCount keystrokeCount = keystrokeMgr.getKeystrokeCount();
        if (keystrokeCount == null) {
            // create one
            projectName = projectName != null && !projectName.equals("") ? projectName : "Unnamed";
            projectDir = projectDir != null && !projectDir.equals("") ? projectDir : "Untitled";
            // create the keysrtroke count wrapper
            createKeystrokeCountWrapper(projectName, projectDir);

            // now retrieve it from the mgr
            keystrokeCount = keystrokeMgr.getKeystrokeCount();
        }
        return keystrokeCount;
    }

    // this is used to close unended files
    public void handleSelectionChangedEvents(String fileName, Project project) {
        KeystrokeCount keystrokeCount =
                getCurrentKeystrokeCount(project.getName(), project.getProjectFilePath());

        KeystrokeCount.FileInfo fileInfo = keystrokeCount.getSourceByFileName(fileName);
        if (fileInfo == null) {
            return;
        }
        keystrokeCount.endPreviousModifiedFiles(fileName);
    }

    public void handleFileOpenedEvents(String fileName, Project project) {
        KeystrokeCount keystrokeCount =
                getCurrentKeystrokeCount(project.getName(), project.getProjectFilePath());

        KeystrokeCount.FileInfo fileInfo = keystrokeCount.getSourceByFileName(fileName);
        if (fileInfo == null) {
            return;
        }
        keystrokeCount.endPreviousModifiedFiles(fileName);
        fileInfo.open = fileInfo.open + 1;
        int documentLineCount = SoftwareCoUtils.getLineCount(fileName);
        fileInfo.lines = documentLineCount;
        LOG.info("Code Time: file opened: " + fileName);
        tracker.trackEditorAction("file", "open", fileName);
    }

    public void handleFileClosedEvents(String fileName, Project project) {
        KeystrokeCount keystrokeCount =
                getCurrentKeystrokeCount(project.getName(), project.getProjectFilePath());
        KeystrokeCount.FileInfo fileInfo = keystrokeCount.getSourceByFileName(fileName);
        if (fileInfo == null) {
            return;
        }
        fileInfo.close = fileInfo.close + 1;
        LOG.info("Code Time: file closed: " + fileName);
        tracker.trackEditorAction("file", "close", fileName);
    }

    /**
     * Handles character change events in a file
     * @param document
     * @param documentEvent
     */
    public void handleChangeEvents(Document document, DocumentEvent documentEvent) {

        if (document == null) {
            return;
        }

        ApplicationManager.getApplication().executeOnPooledThread(() -> {

            FileDocumentManager instance = FileDocumentManager.getInstance();
            if (instance != null) {
                VirtualFile file = instance.getFile(document);
                if (file != null && !file.isDirectory()) {
                    Editor[] editors = EditorFactory.getInstance().getEditors(document);
                    if (editors != null && editors.length > 0) {
                        String fileName = file.getPath();
                        Project project = editors[0].getProject();

                        if (project != null) {

                            // get the current keystroke count obj
                            KeystrokeCount keystrokeCount =
                                    getCurrentKeystrokeCount(project.getName(), project.getProjectFilePath());

                            // check whether it's a code time file or not
                            // .*\.software.*(data\.json|session\.json|latestKeystrokes\.json|ProjectContributorCodeSummary\.txt|CodeTime\.txt|SummaryInfo\.txt|events\.json|fileChangeSummary\.json)
                            boolean skip = (file == null || file.equals("") || fileName.matches(".*\\.software.*(data\\.json|session\\.json|latestKeystrokes\\.json|ProjectContributorCodeSummary\\.txt|CodeTime\\.txt|SummaryInfo\\.txt|events\\.json|fileChangeSummary\\.json)")) ? true : false;

                            if (!skip && keystrokeCount != null) {

                                KeystrokeCount.FileInfo fileInfo = keystrokeCount.getSourceByFileName(fileName);
                                String syntax = fileInfo.syntax;
                                if (syntax == null || syntax.equals("")) {
                                    // get the grammar
                                    try {
                                        String fileType = file.getFileType().getName();
                                        if (fileType != null && !fileType.equals("")) {
                                            fileInfo.syntax = fileType;
                                        }
                                    } catch (Exception e) {
                                        //
                                    }
                                }
                                if (documentEvent.getOldLength() > 0) {
                                    //it's a delete
                                    fileInfo.delete += 1;
                                    fileInfo.netkeys = fileInfo.add - fileInfo.delete;
                                    LOG.info("Code Time: delete incremented");
                                } else {
                                    // it's an add
                                    if (documentEvent.getNewLength() > 8) {
                                        // it's a paste
                                        fileInfo.paste += 1;
                                        fileInfo.charsPasted += documentEvent.getNewLength();
                                    } else {
                                        fileInfo.add += 1;
                                        fileInfo.netkeys = fileInfo.add - fileInfo.delete;
                                        LOG.info("Code Time: add incremented");
                                    }
                                }

                                keystrokeCount.keystrokes += 1;

                                int documentLineCount = document.getLineCount();
                                int savedLines = fileInfo.lines;
                                if (savedLines > 0) {
                                    int diff = documentLineCount - savedLines;
                                    if (diff < 0) {
                                        fileInfo.linesRemoved = fileInfo.linesRemoved + Math.abs(diff);
                                        LOG.info("Code Time: lines removed incremented");
                                    } else if (diff > 0) {
                                        fileInfo.linesAdded = fileInfo.linesAdded + diff;
                                        LOG.info("Code Time: lines added incremented");
                                    }
                                }
                                fileInfo.lines = documentLineCount;

                                // update the latest payload
                                keystrokeCount.updateLatestPayloadLazily();
                            }
                        }
                    }

                }
            }
        });
    }

    public void createKeystrokeCountWrapper(String projectName, String projectFilepath) {
        //
        // Create one since it hasn't been created yet
        // and set the start time (in seconds)
        //
        KeystrokeCount keystrokeCount = new KeystrokeCount();

        KeystrokeProject keystrokeProject = new KeystrokeProject( projectName, projectFilepath );
        keystrokeCount.setProject( keystrokeProject );

        //
        // Update the manager with the newly created KeystrokeCount object
        //
        keystrokeMgr.setKeystrokeCount(projectName, keystrokeCount);
    }


    private String getProjectDirectory(String projectName, String fileName) {
        String projectDirectory = "";
        if ( projectName != null && projectName.length() > 0 &&
                fileName != null && fileName.length() > 0 &&
                fileName.indexOf(projectName) > 0 ) {
            projectDirectory = fileName.substring( 0, fileName.indexOf( projectName ) - 1 );
        }
        return projectDirectory;
    }
}
