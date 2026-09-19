package dev.turnfab;
	
import java.awt.SplashScreen;
import java.util.List;

import com.cathive.fx.guice.GuiceApplication;
import com.cathive.fx.guice.GuiceFXMLLoader;
import com.google.inject.Inject;
import com.google.inject.Module;

import it.sauronsoftware.junique.AlreadyLockedException;
import it.sauronsoftware.junique.JUnique;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;


public class TurnfabApp extends GuiceApplication {

    @Inject private GuiceFXMLLoader fxmlLoader;
	
    @Override
    public void init(List<Module> modules) throws Exception {
        modules.add(new TurnfabGuice());
    }
    
	@Override
	public void start(Stage primaryStage) {
		try {
			Parent root = fxmlLoader.load(getClass().getResource("Root.fxml")).getRoot();
			Scene scene = new Scene(root);
			scene.getStylesheets().add(getClass().getResource("application.css").toExternalForm());
			primaryStage.setScene(scene);
			primaryStage.setTitle("Turnfab");
		    primaryStage.addEventHandler(WindowEvent.WINDOW_SHOWN, (evt)->{if (SplashScreen.getSplashScreen()!=null) SplashScreen.getSplashScreen().close();});
			primaryStage.show();
		} catch(Exception e) {
			e.printStackTrace();
		}
	}
	
	public static void main(String[] args) {

		// fixes WebKit rendering on unscaled screens (scaled to 100%)
		// but breaks auto-scaling on scaled screens (hi-res laptops)
		System.setProperty("glass.win.uiScale", "100%");

		Class<TurnfabApp> appClass = TurnfabApp.class;
		try {
			JUnique.acquireLock(appClass.getName());
			GuiceApplication.launch(appClass);
		} catch (AlreadyLockedException e) {
			System.out.println("Another instance of "+appClass.getName()+" was detected, shutting down.");
		}
		if (SplashScreen.getSplashScreen()!=null) SplashScreen.getSplashScreen().close();
		System.out.println("END main()");
		System.exit(0);
	}
}
