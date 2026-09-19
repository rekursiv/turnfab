module dev.turnfab {
	
	exports dev.turnfab;
	opens dev.turnfab;

	requires java.desktop;
	requires java.logging;
	requires javafx.base;
	requires javafx.controls;
	requires javafx.fxml;
	requires javafx.graphics;
	requires javafx.web;
	requires junique;
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


}
