package com.rarchives.ripme.ripper;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.rarchives.ripme.ui.RipStatusMessage;
import com.rarchives.ripme.ui.RipStatusMessage.STATUS;
import com.rarchives.ripme.utils.Utils;

// Should this file even exist? It does the same thing as abstractHTML ripper

/**'
 * For ripping delicious albums off the interwebz.
 */
public abstract class AlbumRipper extends AbstractRipper {

    private Map<URL, File> itemsPending = Collections.synchronizedMap(new HashMap<URL, File>());
    private Map<URL, File> itemsCompleted = Collections.synchronizedMap(new HashMap<URL, File>());
    private Map<URL, String> itemsErrored = Collections.synchronizedMap(new HashMap<URL, String>());

    protected AlbumRipper(URL url) throws IOException {
        super(url);
    }

    public abstract boolean canRip(URL url);
    public abstract URL sanitizeURL(URL url) throws MalformedURLException;
    public abstract void rip() throws IOException;
    public abstract String getHost();
    public abstract String getGID(URL url) throws MalformedURLException;

    protected boolean allowDuplicates() {
        return false;
    }

    @Override
    /**
     * Returns total amount of files attempted.
     */
    public int getCount() {
        return itemsCompleted.size() + itemsErrored.size();
    }

    @Override
    /**
     * Queues multiple URLs of single images to download from a single Album URL
     */
    public boolean addURLToDownload(URL url, File saveAs, String referrer, Map<String,String> cookies, Boolean getFileExtFromMIME) {

        if (checkIsTest() || checkDuplicated(url, saveAs)) return false;

        if (checkUrlOnlyFile()) {
            WriteTheUrlTOFile(url);
        }
        else {
            enqueueDownloadThread(url, saveAs, referrer, cookies, getFileExtFromMIME);
        }

        return true;
    }

    private void enqueueDownloadThread(URL url, File saveAs, String referrer, Map<String, String> cookies, Boolean getFileExtFromMIME) {
        itemsPending.put(url, saveAs);
        DownloadFileThread dft = new DownloadFileThread(url, saveAs,  this, getFileExtFromMIME);
        if (referrer != null) {
            dft.setReferrer(referrer);
        }
        if (cookies != null) {
            dft.setCookies(cookies);
        }
        threadPool.addThread(dft);
    }

    private void WriteTheUrlTOFile(URL url) {
        // Output URL to file
        String urlFile = this.workingDir + File.separator + "urls.txt";
        try (FileWriter fileWriter = new FileWriter(urlFile, true)) {
            fileWriter.write(url.toExternalForm());
            fileWriter.write(System.lineSeparator());
            itemsCompleted.put(url, new File(urlFile));
        } catch (IOException e) {
            LOGGER.error("Error while writing to " + urlFile, e);
        }
    }

    private static boolean checkUrlOnlyFile() {
        return Utils.getConfigBoolean("urls_only.save", false);
    }

    private boolean checkDuplicated(URL url, File saveAs) {
        if (!allowDuplicates()
                && ( itemsPending.containsKey(url)
                  || itemsCompleted.containsKey(url)
                  || itemsErrored.containsKey(url) )) {
            // Item is already downloaded/downloading, skip it.
            LOGGER.info("[!] Skipping " + url + " -- already attempted: " + Utils.removeCWD(saveAs));
            return true;
        }
        return false;
    }

    private boolean checkIsTest() {
        // Only download one file if this is a test.
        if (super.isThisATest() && (itemsPending.size() > 0 || itemsCompleted.size() > 0 || itemsErrored.size() > 0)) {
            stop();
            return true;
        }
        return false;
    }

    private boolean checkTestCase() {
        return super.isThisATest() &&
                (itemsPending.size() > 0 || itemsCompleted.size() > 0 || itemsErrored.size() > 0);
    }

    @Override
    public boolean addURLToDownload(URL url, File saveAs) {
        return addURLToDownload(url, saveAs, null, null, false);
    }

    /**
     * Queues image to be downloaded and saved.
     * Uses filename from URL to decide filename.
     * @param url
     *      URL to download
     * @return
     *      True on success
     */
    protected boolean addURLToDownload(URL url) {
        // Use empty prefix and empty subdirectory
        return addURLToDownload(url, "", "");
    }

    @Override
    /**
     * Cleans up & tells user about successful download
     */
    public void downloadCompleted(URL url, File saveAs) {
        if (observer == null) {
            return;
        }
        try {
            postDownloadOperation(url, saveAs);
            checkIfComplete();
        } catch (Exception e) {
            LOGGER.error("Exception while updating observer: ", e);
        }
    }

    private void postDownloadOperation(URL url, File saveAs) {
        String path = Utils.removeCWD(saveAs);
        RipStatusMessage msg = new RipStatusMessage(STATUS.DOWNLOAD_COMPLETE, path);
        updateErrorFile(url, saveAs);
        observer.update(this, msg);
    }

    @Override
    /**
     * Cleans up & tells user about failed download.
     */
    public void downloadErrored(URL url, String reason) {
        if (observer == null) {
            return;
        }
        updateErrorReason(url, reason);
        observer.update(this, new RipStatusMessage(STATUS.DOWNLOAD_ERRORED, url + " : " + reason));
        checkIfComplete();
    }

    private void updateErrorReason(URL url, String reason) {
        itemsPending.remove(url);
        itemsErrored.put(url, reason);
    }

    @Override
    /**
     * Tells user that a single file in the album they wish to download has
     * already been downloaded in the past.
     */
    public void downloadExists(URL url, File file) {
        if (observer == null) {
            return;
        }
        updateErrorFile(url, file);
        observer.update(this,new RipStatusMessage(STATUS.DOWNLOAD_WARN, url + " already saved as " + file.getAbsolutePath()));
        checkIfComplete();
    }

   private void sendErrorMessage(File file, String s) {
       RipStatusMessage errorMessage = new RipStatusMessage(STATUS.DOWNLOAD_WARN, url + s + file.getAbsolutePath());
       observer.update(this,errorMessage);
   }

    private void updateErrorFile(URL url, File file) {
        itemsPending.remove(url);
        itemsCompleted.put(url, file);
    }

    /**
     * Notifies observers and updates state if all files have been ripped.
     */
    @Override
    protected void checkIfComplete() {
        if (observer == null) {
            return;
        }
        if (itemsPending.isEmpty()) {
            super.checkIfComplete();
        }
    }

    /**
     * Sets directory to save all ripped files to.
     * @param url
     *      URL to define how the working directory should be saved.
     * @throws
     *      IOException
     */
    @Override
    public void setWorkingDir(URL url) throws IOException {
        String path = createWorkingPath();

        this.workingDir = new File(path);
        createWorkingDirIfDoesntExist();

        LOGGER.debug("Set working directory to: " + this.workingDir);
    }


    private String createWorkingPath() throws IOException {
        String path = Utils.getWorkingDirectory().getCanonicalPath();
        if (!path.endsWith(File.separator)) {
            path += File.separator;
        }

        String title = Utils.getConfigBoolean("album_titles.save", true) ? getAlbumTitle(this.url) : super.getAlbumTitle(this.url);
        LOGGER.debug("Using album title '" + title + "'");

        title = Utils.filesystemSafe(title);
        path = Utils.getOriginalDirectory(path+title) + File.separator;   // check for case sensitive (unix only)

        return path;
    }

    private void createWorkingDirIfDoesntExist() {
        if (!this.workingDir.exists()) {
            LOGGER.info("[+] Creating directory: " + Utils.removeCWD(this.workingDir));
            this.workingDir.mkdirs();
        }
    }

    /**
     * @return
     *      Integer between 0 and 100 defining the progress of the album rip.
     */
    @Override
    public int getCompletionPercentage() {
        double total = itemsPending.size()  + itemsErrored.size() + itemsCompleted.size();
        return (int) (100 * ( (total - itemsPending.size()) / total));
    }

    /**
     * @return
     *      Human-readable information on the status of the current rip.
     */
    @Override
    public String getStatusText() {
        StringBuilder strBuilder = new StringBuilder();
        strBuilder.append(getCompletionPercentage())
          .append("% ")
          .append("- Pending: "  ).append(itemsPending.size())
          .append(", Completed: ").append(itemsCompleted.size())
          .append(", Errored: "  ).append(itemsErrored.size());
        return strBuilder.toString();
    }
}
