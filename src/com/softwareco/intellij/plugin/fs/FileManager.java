package com.softwareco.intellij.plugin.fs;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx;
import com.intellij.openapi.preview.PreviewManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.softwareco.intellij.plugin.AsyncManager;
import com.softwareco.intellij.plugin.SoftwareCo;
import com.softwareco.intellij.plugin.SoftwareCoUtils;
import com.softwareco.intellij.plugin.SoftwareResponse;
import com.softwareco.intellij.plugin.wallclock.WallClockManager;
import org.apache.http.client.methods.HttpPost;

import javax.swing.*;
import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.logging.Logger;

public class FileManager {

    public static final Logger log = Logger.getLogger("FileManager");

    private static FileManager instance = null;


    public static FileManager getInstance() {
        if (instance == null) {
            synchronized (log) {
                if (instance == null) {
                    instance = new FileManager();
                }
            }
        }
        return instance;
    }

    private FileManager() {
        //
    }

    public static void writeData(String file, Object o) {
        if (o == null) {
            return;
        }
        File f = new File(file);
        final String content = SoftwareCo.gson.toJson(o);

        Writer writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(f), Charset.forName("UTF-8")));
            writer.write(content);
        } catch (IOException e) {
            log.warning("Code Time: Error writing content: " + e.getMessage());
        } finally {
            try {writer.close();} catch (Exception ex) {/*ignore*/}
        }
    }

    public static void appendData(String file, Object o) {
        if (o == null) {
            return;
        }
        File f = new File(file);
        String content = SoftwareCo.gson.toJson(o);
        if (SoftwareCoUtils.isWindows()) {
            content += "\r\n";
        } else {
            content += "\n";
        }
        try {
            log.info("Code Time: Storing content: " + content);
            Writer output;
            output = new BufferedWriter(new FileWriter(f, true));  //clears file every time
            output.append(content);
            output.close();
        } catch (Exception e) {
            log.warning("Code Time: Error appending content: " + e.getMessage());
        }
    }

    public static JsonObject getFileContentAsJson(String file) {
        JsonObject data = null;

        File f = new File(file);
        if (f.exists()) {
            try {
                byte[] encoded = Files.readAllBytes(Paths.get(file));
                String content = new String(encoded, Charset.forName("UTF-8"));
                if (content != null) {
                    // json parse it
                    data = SoftwareCo.jsonParser.parse(content).getAsJsonObject();
                }
            } catch (Exception e) {
                log.warning("Code Time: Error trying to read and parse: " + e.getMessage());
            }
        }
        return data;
    }

    public static JsonArray getFileContentAsJsonArray(String file) {
        JsonArray jsonArray = null;

        File f = new File(file);
        if (f.exists()) {
            try {
                byte[] encoded = Files.readAllBytes(Paths.get(file));
                String content = new String(encoded, Charset.forName("UTF-8"));
                if (content != null) {
                    // json parse it
                    try {
                        jsonArray = SoftwareCo.jsonParser.parse(content).getAsJsonArray();
                    } catch (Exception e1) {
                        // maybe it's a json object, return it within a json array if this succeeds
                        try {
                            JsonObject obj = SoftwareCo.jsonParser.parse(content).getAsJsonObject();
                            jsonArray = new JsonArray();
                            jsonArray.add(obj);
                        } catch (Exception e2) {
                            log.warning("Code Time: Error trying to read and parse: " + e2.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                log.warning("Code Time: Error trying to read and parse: " + e.getMessage());
            }
        }

        return jsonArray;
    }

    public static void deleteFile(String file) {
        File f = new File(file);
        // if the file exists, delete it
        if (f.exists()) {
            f.delete();
        }
    }

    public static void sendBatchData(String file, String api) {
        File f = new File(file);
        if (f.exists()) {
            // found a data file, check if there's content
            StringBuffer sb = new StringBuffer();
            try {
                FileInputStream fis = new FileInputStream(f);

                // Construct BufferedReader from InputStreamReader
                BufferedReader br = new BufferedReader(new InputStreamReader(fis));

                String line = null;
                // add commas to the end of each line
                while ((line = br.readLine()) != null) {
                    if (line.length() > 0) {
                        sb.append(line).append(",");
                    }
                }

                br.close();

                if (sb.length() > 0) {
                    // check to see if it's already an array
                    String payloads = sb.toString();
                    payloads = payloads.substring(0, payloads.lastIndexOf(","));
                    payloads = "[" + payloads + "]";

                    JsonArray jsonArray = (JsonArray) SoftwareCo.jsonParser.parse(payloads);

                    // delete the file
                    deleteFile(file);

                    JsonArray batch = new JsonArray();
                    // go through the array about 50 at a time
                    for (int i = 0; i < jsonArray.size(); i++) {
                        batch.add(jsonArray.get(i));
                        if (i > 0 && i % 50 == 0) {
                            String payloadData = SoftwareCo.gson.toJson(batch);
                            SoftwareResponse resp =
                                    SoftwareCoUtils.makeApiCall(api, HttpPost.METHOD_NAME, payloadData);
                            if (!resp.isOk()) {
                                // add these back to the offline file
                                log.info("Code Time: Unable to send batch data: " + resp.getErrorMessage());
                            }
                            batch = new JsonArray();
                        }
                    }
                    if (batch.size() > 0) {
                        String payloadData = SoftwareCo.gson.toJson(batch);
                        SoftwareResponse resp =
                                SoftwareCoUtils.makeApiCall("/data/batch", HttpPost.METHOD_NAME, payloadData);
                        if (!resp.isOk()) {
                            // add these back to the offline file
                            log.info("Code Time: Unable to send batch data: " + resp.getErrorMessage());
                        }
                    }

                } else {
                    log.info("Code Time: No offline data to send");
                }
            } catch (Exception e) {
                log.warning("Code Time: Error trying to read and send offline data: " + e.getMessage());
            }
        }
    }

    public static String getFileContent(String file) {
        String content = null;

        File f = new File(file);
        if (f.exists()) {
            try {
                byte[] encoded = Files.readAllBytes(Paths.get(file));
                content = new String(encoded, Charset.forName("UTF-8"));
            } catch (Exception e) {
                log.warning("Code Time: Error trying to read and parse: " + e.getMessage());
            }
        }
        return content;
    }

    public static void saveFileContent(String file, String content) {
        File f = new File(file);

        Writer writer = null;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(f), Charset.forName("UTF-8")));
            writer.write(content);
        } catch (IOException ex) {
            // Report
        } finally {
            try {writer.close();} catch (Exception ex) {/*ignore*/}
        }
    }

    private static String getLocalReadmeFile() {
        return "README.md";
    }

    public void openReadmeFile() {
        Project p = SoftwareCoUtils.getOpenProject();
        if (p == null) {
            return;
        }
        // Getting Resource as file object
        URL url = getClass().getResource("/com/softwareco/intellij/plugin/assets/" + getLocalReadmeFile());
        File f = new File(url.getFile());

        VirtualFile vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f);
        // TODO: figure out how to show it with only the preview window
        // OpenFileDescriptor descriptor = new OpenFileDescriptor(p, vFile);
        FileEditorManagerEx fileEdMgr = FileEditorManagerEx.getInstanceEx(p);
        fileEdMgr.updateFilePresentation(vFile);
        fileEdMgr.openFile(vFile, true);
        // FileEditorManager.getInstance(p).openTextEditor(descriptor, true);
    }
}
