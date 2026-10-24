// jdk.jsobject is what javafx.web uses to let a page call back into the JVM. The module itself is
// deprecated for removal, not our use of it, so it gets the same allowance the class does.
@SuppressWarnings("removal")
module dev.turnfab {
	
	exports dev.turnfab;
	opens dev.turnfab;

	requires java.desktop;
	requires java.logging;
	requires jdk.jsobject;
	requires javafx.base;
	requires javafx.controls;
	requires javafx.fxml;
	requires javafx.graphics;
	requires javafx.web;
	requires org.controlsfx.controls;
	requires com.google.common;
	requires com.google.guice;
	requires com.cathive.fx.guice;
	requires jakarta.inject;
	requires java.inject;
	requires com.fasterxml.jackson.annotation;

	requires langchain4j;
	requires langchain4j.mcp;
	requires langchain4j.core;
	requires langchain4j.open.ai;
	requires langchain4j.http.client;
	requires langchain4j.http.client.jdk;
	requires java.net.http;
	requires com.fasterxml.jackson.databind;
	requires com.fasterxml.jackson.dataformat.yaml;


}
