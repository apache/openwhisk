package openwhisk.java.action;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Base64;
import java.util.jar.Attributes;

import com.google.gson.JsonObject;

public class JarLoader extends URLClassLoader {
	private final String mainClassName;
	private final Class<?> mainClass;
	private final Method mainMethod;
	
	public static Path saveBase64EncodedFile(InputStream encoded) throws Exception {
		Base64.Decoder decoder = Base64.getDecoder();
		
		InputStream decoded = decoder.wrap(encoded);
		
		File destinationFile = File.createTempFile("useraction", ".jar");
		destinationFile.deleteOnExit();
		Path destinationPath = destinationFile.toPath();
		
		System.out.println(destinationPath);
		
		Files.copy(decoded, destinationPath, StandardCopyOption.REPLACE_EXISTING);
		
		return destinationPath;
	}

	public JarLoader(Path jarPath, String mainClassName) throws MalformedURLException, ClassNotFoundException, NoSuchMethodException, SecurityException {
		super(new URL[] { jarPath.toUri().toURL() });
		
		this.mainClassName = mainClassName;
		this.mainClass = loadClass(mainClassName);
		
		Method m = mainClass.getMethod("main", new Class[] { JsonObject.class });
		m.setAccessible(true);
		int modifiers = m.getModifiers();
		if(m.getReturnType() != JsonObject.class || !Modifier.isStatic(modifiers) || !Modifier.isPublic(modifiers)) {
			throw new NoSuchMethodException("main");
		}
		
		this.mainMethod = m;
	}
	
	public JsonObject invokeMain(JsonObject arg) throws Exception {
		return (JsonObject)mainMethod.invoke(null, arg);
	}
}
