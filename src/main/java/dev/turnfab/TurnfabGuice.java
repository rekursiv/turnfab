package dev.turnfab;

import java.io.IOException;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;

import com.google.common.eventbus.EventBus;
import com.google.inject.AbstractModule;
import com.google.inject.TypeLiteral;
import com.google.inject.matcher.Matchers;
import com.google.inject.spi.InjectionListener;
import com.google.inject.spi.TypeEncounter;
import com.google.inject.spi.TypeListener;

public class TurnfabGuice extends AbstractModule {

	protected final EventBus eventBus = new EventBus();
	protected final ConfigManager<TurnfabConfig> cfgMgr = new ConfigManager<TurnfabConfig>(TurnfabConfig.class, "ModelTestConfig.json");
	protected TurnfabConfig config;
	
	@Override
	protected void configure() {
		setupConfig();
		setupLogging();
		setupEventBus();
	}
	
	protected void setupConfig() {
		config = cfgMgr.load();
		bind(new TypeLiteral<ConfigManager<TurnfabConfig>>() {}).toInstance(cfgMgr);
		bind(TurnfabConfig.class).toInstance(config);
	}

	protected void setupEventBus() {
		bind(EventBus.class).toInstance(eventBus);
		bindListener(Matchers.any(), new TypeListener() {
			public <I> void hear(TypeLiteral<I> typeLiteral, TypeEncounter<I> typeEncounter) {
				typeEncounter.register(new InjectionListener<I>() {
					public void afterInjection(I i) {
						eventBus.register(i);
//						System.out.println("EventBus registered "+i.getClass().getName());
					}
				});
			}
		});
	}
	
	protected void setupLogging() {

		LogManager.getLogManager().reset();

		// setup logging to console
		if (config.logToConsole) {
			Handler ch = new ConsoleHandler();
			ch.setFormatter(new ConsoleFormatter());
			ch.setLevel(Level.ALL);
			LogManager.getLogManager().getLogger("").addHandler(ch);
		}
		
		// setup logging to event bus
//		EbLogHandler logCon = new EbLogHandler(eventBus);
//		logCon.setLevel(Level.ALL);
//		LogManager.getLogManager().getLogger("").addHandler(logCon);
		
		// setup logging to file
		if (config.logToFile) {
			try {
				LogManager.getLogManager().getLogger("").addHandler(new FileHandler("log/ModelTest-%u-%g.log", 0, 10));
			} catch (SecurityException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		// log config errors
		cfgMgr.logErrorsIfAny();
		
		
	}

}
