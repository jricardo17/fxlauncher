package fxlauncher;

import com.sun.javafx.application.ParametersImpl;
import com.sun.javafx.application.PlatformImpl;
import java.io.*;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.DialogPane;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

@SuppressWarnings("unchecked")
public class Launcher extends Application {

    private static final Logger log = Logger.getLogger("Launcher");

    private Application app;
    private Stage primaryStage;
    private Stage stage;
    private UIProvider uiProvider;
    private StackPane root;

    private final AbstractLauncher superLauncher = new AbstractLauncher<Application>() {

        private final String defaultMessageTitle = "Error";

        private final String defaultMessageHeaderText = "Unable to connect to application server.";

        private final String defaultMessageContentText = "Check your network connection and try again.";

        @Override
        protected Parameters getParameters() {
            return Launcher.this.getParameters();
        }

        @Override
        protected void updateProgress(double progress) {
            Platform.runLater(() -> uiProvider.updateProgress(progress));
        }

        @Override
        protected void createApplication(Class<Application> appClass) {
            PlatformImpl.runAndWait(()
                    -> {
                try {
                    app = appClass.newInstance();
                } catch (Throwable t) {
                    reportError("Error creating app class", t);
                }
            });
        }

        @Override
        protected void reportError(String title, Throwable error) {
            log.log(Level.WARNING, title, error);

            Platform.runLater(() -> {

                Alert alert = new Alert(Alert.AlertType.ERROR);
                URL url = this.getClass().getResource("/styles/dialogs-style.css");

                if (url != null) {
                    DialogPane dialogPane = alert.getDialogPane();
                    dialogPane.getStylesheets().add(url.toExternalForm());
                }

                InputStream messageStream = this.getClass().getResourceAsStream("/properties/error-message.properties");

                if (messageStream != null) {

                    Properties p = new Properties();

                    try {

                        p.load(messageStream);
                        alert.setTitle(p.getProperty("message.title", this.defaultMessageTitle));
                        alert.setHeaderText(p.getProperty("message.header.text", this.defaultMessageHeaderText));
                        alert.setContentText(p.getProperty("message.content.text", this.defaultMessageContentText));

                    } catch (IOException ex) {
                        log.log(Level.WARNING, String.format("Error during %s phase", superLauncher.getPhase()), ex);
                        System.exit(1);
                    }

                } else {

                    alert.setTitle(this.defaultMessageTitle);
                    alert.setHeaderText(this.defaultMessageHeaderText);
                    alert.setContentText(this.defaultMessageContentText);

                }

                alert.showAndWait();
                Platform.exit();

            });
        }

        @Override
        protected void setupClassLoader(ClassLoader classLoader) {
            FXMLLoader.setDefaultClassLoader(classLoader);
            Platform.runLater(() -> Thread.currentThread().setContextClassLoader(classLoader));
        }

    };

    /**
     * Check if a new version is available and return the manifest for the new
     * version or null if no update.
     *
     * Note that updates will only be detected if the application was actually
     * launched with FXLauncher.
     *
     * @return The manifest for the new version if available
     */
    public static FXManifest checkForUpdate() throws IOException {
        // We might be called even when FXLauncher wasn't used to start the application
        if (AbstractLauncher.manifest == null) {
            return null;
        }
        FXManifest manifest = FXManifest.load(URI.create(AbstractLauncher.manifest.uri + "/app.xml"));
        return manifest.equals(AbstractLauncher.manifest) ? null : manifest;
    }

    /**
     * Initialize the UI Provider by looking for an UIProvider inside the
     * launcher or fallback to the default UI.
     * <p>
     * A custom implementation must be embedded inside the launcher jar, and
     * /META-INF/services/fxlauncher.UIProvider must point to the new
     * implementation class.
     * <p>
     * You must do this manually/in your build right around the "embed manifest"
     * step.
     */
    public void init() throws Exception {
        Iterator<UIProvider> providers = ServiceLoader.load(UIProvider.class).iterator();
        uiProvider = providers.hasNext() ? providers.next() : new DefaultUIProvider();
    }

    public void start(Stage primaryStage) throws Exception {
        this.primaryStage = primaryStage;
        stage = new Stage(StageStyle.UNDECORATED);
        root = new StackPane();
        final boolean[] filesUpdated = new boolean[1];

        Scene scene = new Scene(root);
        stage.setScene(scene);

        superLauncher.setupLogFile();
        superLauncher.checkSSLIgnoreflag();
        this.uiProvider.init(stage);
        root.getChildren().add(uiProvider.createLoader());

        stage.show();

        new Thread(() -> {
            Thread.currentThread().setName("FXLauncher-Thread");
            try {
                superLauncher.updateManifest();
                createUpdateWrapper();
                filesUpdated[0] = superLauncher.syncFiles();
            } catch (Exception ex) {
                log.log(Level.WARNING, String.format("Error during %s phase", superLauncher.getPhase()), ex);
                if (superLauncher.checkIgnoreUpdateErrorSetting()) {
                    superLauncher.reportError(String.format("Error during %s phase", superLauncher.getPhase()), ex);
                    System.exit(1);
                }
            }

            try {
                superLauncher.createApplicationEnvironment();
                launchAppFromManifest(filesUpdated[0]);
            } catch (Exception ex) {
                superLauncher.reportError(String.format("Error during %s phase", superLauncher.getPhase()), ex);
            }

        }).start();
    }

    private void launchAppFromManifest(boolean showWhatsnew) throws Exception {
        superLauncher.setPhase("Application Environment Prepare");
        ParametersImpl.registerParameters(app, new LauncherParams(getParameters(), superLauncher.getManifest()));
        PlatformImpl.setApplicationName(app.getClass());
        superLauncher.setPhase("Application Init");
        app.init();
        superLauncher.setPhase("Application Start");
        log.info("Show whats new dialog? " + showWhatsnew);
        PlatformImpl.runAndWait(()
                -> {
            try {
                if (showWhatsnew && superLauncher.getManifest().whatsNewPage != null) {
                    showWhatsNewDialog(superLauncher.getManifest().whatsNewPage);
                }

                // Lingering update screen will close when primary stage is shown
                if (superLauncher.getManifest().lingeringUpdateScreen) {
                    primaryStage.showingProperty().addListener(observable -> {
                        if (stage.isShowing()) {
                            stage.close();
                        }
                    });
                } else {
                    stage.close();
                }

                app.start(primaryStage);
            } catch (Exception ex) {
                superLauncher.reportError("Failed to start application", ex);
            }
        });
    }

    private void showWhatsNewDialog(String whatsNewPage) {
        WebView view = new WebView();
        view.getEngine().load(Launcher.class.getResource(whatsNewPage).toExternalForm());
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("What's new");
        alert.setHeaderText("New in this update");
        alert.getDialogPane().setContent(view);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }

    private void createUpdateWrapper() {
        superLauncher.setPhase("Update Wrapper Creation");

        Platform.runLater(()
                -> {
            Parent updater = uiProvider.createUpdater(superLauncher.getManifest());
            root.getChildren().clear();
            root.getChildren().add(updater);
        });
    }

    public void stop() throws Exception {
        if (app != null) {
            app.stop();
        }
    }
}
