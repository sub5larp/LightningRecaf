package recafx.analysis;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import software.coley.recaf.info.JvmClassInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds network related code: HTTP clients, sockets, remote class loading, URL strings and webhooks.
 */
public final class NetworkAnalysis {
	public enum Kind {
		HTTP("HTTP client"),
		SOCKET("Socket / raw network"),
		REMOTE_CODE("Remote code loading"),
		WEBHOOK("Webhook / exfiltration endpoint"),
		URL_STRING("URL string");

		public final String label;

		Kind(String label) {this.label = label;}
	}

	public record Hit(Kind kind, String detail, String method) {}

	public record ClassFindings(String className, List<Hit> hits) {
		public boolean isEmpty() {return hits.isEmpty();}

		public Set<Kind> kinds() {
			Set<Kind> kinds = EnumSet.noneOf(Kind.class);
			for (Hit hit : hits) kinds.add(hit.kind());
			return kinds;
		}

		/** Strongest kind for coloring / sorting. */
		public Kind primaryKind() {
			Set<Kind> kinds = kinds();
			for (Kind k : Kind.values()) if (kinds.contains(k)) return k;
			return null;
		}

		public String summary() {
			Map<Kind, Integer> counts = new LinkedHashMap<>();
			for (Hit hit : hits) counts.merge(hit.kind(), 1, Integer::sum);
			StringBuilder sb = new StringBuilder();
			counts.forEach((k, v) -> {
				if (!sb.isEmpty()) sb.append(", ");
				sb.append(k.label).append(" (").append(v).append(')');
			});
			return sb.toString();
		}
	}

	// Internal name prefixes / exact names mapped to a kind.
	private static final Map<String, Kind> EXACT = new LinkedHashMap<>();
	private static final Map<String, Kind> PREFIX = new LinkedHashMap<>();

	static {
		for (String n : new String[]{"java/net/URL", "java/net/URLConnection", "java/net/HttpURLConnection",
				"javax/net/ssl/HttpsURLConnection", "java/net/JarURLConnection", "java/net/CookieHandler",
				"java/net/CookieManager", "java/net/Authenticator", "java/net/ProxySelector", "java/net/Proxy"})
			EXACT.put(n, Kind.HTTP);
		for (String n : new String[]{"java/net/Socket", "java/net/ServerSocket", "java/net/DatagramSocket",
				"java/net/MulticastSocket", "java/net/InetAddress", "java/net/Inet4Address", "java/net/Inet6Address",
				"java/net/InetSocketAddress", "java/net/SocketAddress", "java/net/NetworkInterface",
				"java/nio/channels/SocketChannel", "java/nio/channels/ServerSocketChannel",
				"java/nio/channels/DatagramChannel", "java/nio/channels/AsynchronousSocketChannel",
				"java/nio/channels/AsynchronousServerSocketChannel", "javax/net/SocketFactory",
				"javax/net/ServerSocketFactory", "javax/net/ssl/SSLSocket", "javax/net/ssl/SSLSocketFactory",
				"javax/net/ssl/SSLContext", "javax/net/ssl/SSLServerSocket"})
			EXACT.put(n, Kind.SOCKET);
		EXACT.put("java/net/URLClassLoader", Kind.REMOTE_CODE);
		EXACT.put("java/rmi/Naming", Kind.REMOTE_CODE);
		EXACT.put("javax/naming/InitialContext", Kind.REMOTE_CODE);
		EXACT.put("javax/naming/directory/InitialDirContext", Kind.REMOTE_CODE);
		EXACT.put("java/net/http/HttpClient", Kind.HTTP);
		EXACT.put("java/net/http/HttpRequest", Kind.HTTP);
		EXACT.put("java/net/http/HttpResponse", Kind.HTTP);
		EXACT.put("java/net/http/WebSocket", Kind.SOCKET);

		for (String p : new String[]{"java/net/http/", "okhttp3/", "com/squareup/okhttp/", "org/apache/http/",
				"org/apache/hc/", "retrofit2/", "feign/", "kong/unirest/", "org/asynchttpclient/",
				"org/springframework/web/client/", "org/springframework/web/reactive/function/client/",
				"com/google/api/client/http/", "org/jsoup/Connection", "org/jsoup/Jsoup", "io/vertx/core/http/",
				"io/vertx/ext/web/client/", "com/github/kevinsawicki/http/", "com/mashape/unirest/",
				"org/eclipse/jetty/client/", "com/sun/net/httpserver/", "sun/net/www/", "jdk/internal/net/http/",
				"com/mojang/authlib/", "org/java_websocket/", "javax/websocket/", "jakarta/websocket/",
				"club/minnced/discord/webhook/", "net/dv8tion/jda/", "org/apache/commons/net/"})
			PREFIX.put(p, Kind.HTTP);
		for (String p : new String[]{"io/netty/", "reactor/netty/", "org/apache/mina/", "java/rmi/", "javax/rmi/",
				"javax/net/ssl/", "javax/net/"})
			PREFIX.put(p, Kind.SOCKET);
		for (String p : new String[]{"javax/naming/", "java/lang/instrument/"})
			PREFIX.put(p, Kind.REMOTE_CODE);
	}

	private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\b(https?|wss?|ftps?|ldaps?|rmi|jnlp)://[\\w\\-.:@/?#%&=+~\\[\\]]{3,}");
	private static final Pattern WEBHOOK_PATTERN = Pattern.compile("(?i)(discord(?:app)?\\.com/api/webhooks|hooks\\.slack\\.com|api\\.telegram\\.org/bot|pastebin\\.com/raw|ngrok\\.io|ngrok-free|\\.onion\\b|webhook\\.site|requestbin|pipedream\\.net)");
	private static final Pattern IP_PORT_PATTERN = Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)(?::\\d{2,5})?\\b");

	private NetworkAnalysis() {}

	/** @return kind for the internal type name, or {@code null} if the type is not network related. */
	public static Kind classify(String internalName) {
		if (internalName == null || internalName.isEmpty()) return null;
		Kind kind = EXACT.get(internalName);
		if (kind != null) return kind;
		for (Map.Entry<String, Kind> e : PREFIX.entrySet())
			if (internalName.startsWith(e.getKey())) return e.getValue();
		return null;
	}

	public static Kind classifyString(String value) {
		if (value == null || value.length() < 6) return null;
		if (WEBHOOK_PATTERN.matcher(value).find()) return Kind.WEBHOOK;
		if (URL_PATTERN.matcher(value).find()) return Kind.URL_STRING;
		if (value.length() <= 40 && IP_PORT_PATTERN.matcher(value).matches() && !value.startsWith("127.")
				&& !value.startsWith("0.") && !value.equals("255.255.255.255"))
			return Kind.URL_STRING;
		return null;
	}

	public static ClassFindings analyze(JvmClassInfo info) {
		return analyze(info, null);
	}

	static ClassFindings analyze(JvmClassInfo info, org.objectweb.asm.tree.ClassNode node) {
		List<Hit> hits = new ArrayList<>();
		Set<String> seen = new LinkedHashSet<>();
		try {
			ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9) {
				@Override
				public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
					addType(hits, seen, superName, null, "extends");
					if (interfaces != null)
						for (String itf : interfaces) addType(hits, seen, itf, null, "implements");
				}

				@Override
				public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
					addDesc(hits, seen, descriptor, null, "field " + name);
					if (value instanceof String s) addString(hits, seen, s, null);
					return null;
				}

				@Override
				public MethodVisitor visitMethod(int access, String mName, String mDesc, String signature, String[] exceptions) {
					String method = mName + mDesc;
					addDesc(hits, seen, mDesc, method, "signature");
					return new MethodVisitor(Opcodes.ASM9) {
						@Override
						public void visitMethodInsn(int opcode, String owner, String name, String descriptor, boolean isInterface) {
							addType(hits, seen, owner, method, "call " + simple(owner) + "." + name + "()");
							if (owner.equals("java/lang/Class") && name.equals("forName"))
								return; // Too common to flag on its own.
							if (owner.equals("java/lang/Runtime") && name.equals("exec"))
								add(hits, seen, Kind.REMOTE_CODE, "Runtime.exec()", method);
							if (owner.equals("java/lang/ProcessBuilder"))
								add(hits, seen, Kind.REMOTE_CODE, "ProcessBuilder", method);
						}

						@Override
						public void visitTypeInsn(int opcode, String type) {
							addType(hits, seen, type, method, opcode == Opcodes.NEW ? "new " + simple(type) : "type " + simple(type));
						}

						@Override
						public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
							addType(hits, seen, owner, method, "field access " + simple(owner) + "." + name);
							addDesc(hits, seen, descriptor, method, "field " + name);
						}

						@Override
						public void visitLdcInsn(Object value) {
							if (value instanceof String s) addString(hits, seen, s, method);
							else if (value instanceof Type t) addDesc(hits, seen, t.getDescriptor(), method, "class constant");
						}

						@Override
						public void visitInvokeDynamicInsn(String name, String descriptor, Handle bsm, Object... args) {
							for (Object arg : args) {
								if (arg instanceof Handle h) addType(hits, seen, h.getOwner(), method, "method handle " + simple(h.getOwner()) + "." + h.getName());
								else if (arg instanceof String s) addString(hits, seen, s, method);
							}
						}
					};
				}
			};
			if (node == null) info.getClassReader().accept(visitor, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
			else node.accept(visitor);
		} catch (Throwable ignored) {
			// Malformed class, report what we have.
		}
		return new ClassFindings(info.getName(), Collections.unmodifiableList(hits));
	}

	private static void addType(List<Hit> hits, Set<String> seen, String internalName, String method, String context) {
		Kind kind = classify(internalName);
		if (kind != null) add(hits, seen, kind, context + " [" + internalName.replace('/', '.') + "]", method);
	}

	private static void addDesc(List<Hit> hits, Set<String> seen, String descriptor, String method, String context) {
		if (descriptor == null || descriptor.indexOf('L') < 0) return;
		try {
			Type type = descriptor.startsWith("(") ? Type.getMethodType(descriptor) : Type.getType(descriptor);
			if (type.getSort() == Type.METHOD) {
				for (Type arg : type.getArgumentTypes()) addDesc(hits, seen, arg.getDescriptor(), method, context);
				addDesc(hits, seen, type.getReturnType().getDescriptor(), method, context);
			} else if (type.getSort() == Type.ARRAY) {
				addDesc(hits, seen, type.getElementType().getDescriptor(), method, context);
			} else if (type.getSort() == Type.OBJECT) {
				addType(hits, seen, type.getInternalName(), method, context);
			}
		} catch (Throwable ignored) {}
	}

	private static void addString(List<Hit> hits, Set<String> seen, String value, String method) {
		Kind kind = classifyString(value);
		if (kind != null) {
			String shown = value.length() > 120 ? value.substring(0, 117) + "..." : value;
			add(hits, seen, kind, "\"" + shown.replace("\n", "\\n") + "\"", method);
		}
	}

	private static void add(List<Hit> hits, Set<String> seen, Kind kind, String detail, String method) {
		if (seen.add(kind + "|" + detail + "|" + method))
			hits.add(new Hit(kind, detail, method));
	}

	private static String simple(String internalName) {
		int i = internalName.lastIndexOf('/');
		return i < 0 ? internalName : internalName.substring(i + 1);
	}

	// ---------------------------------------------------------------------------------------------
	// Decompiled source matching (for editor line flags)
	// ---------------------------------------------------------------------------------------------

	public record LineHit(int line, Kind kind, String detail) {}

	private static final Pattern SOURCE_HTTP = Pattern.compile("\\b(HttpURLConnection|HttpsURLConnection|HttpClient|HttpRequest|HttpResponse|URLConnection|OkHttpClient|HttpGet|HttpPost|HttpPut|HttpDelete|CloseableHttpClient|HttpClients|Unirest|Retrofit|WebClient|RestTemplate|Jsoup\\.connect|HttpsClient|WebhookClient|JarURLConnection|CookieManager|URLStreamHandler)\\b|\\bnew\\s+URL\\s*\\(|\\bURL\\.of\\(|\\.openConnection\\s*\\(|\\.openStream\\s*\\(|\\bjava\\.net\\.(?!URI\\b|URLEncoder|URLDecoder)|\\bokhttp3\\.|\\borg\\.apache\\.http|\\bjava\\.net\\.http\\.|\\bURI\\.create\\(\\s*\"https?://");
	private static final Pattern SOURCE_SOCKET = Pattern.compile("\\b(ServerSocket|DatagramSocket|MulticastSocket|SocketChannel|ServerSocketChannel|DatagramChannel|InetAddress|Inet4Address|Inet6Address|InetSocketAddress|SSLSocket|SSLSocketFactory|SSLContext|SocketFactory|WebSocket|Bootstrap|ServerBootstrap|NioEventLoopGroup|EventLoopGroup|ChannelInitializer)\\b|\\bnew\\s+Socket\\s*\\(|\\bio\\.netty\\.|\\bjavax\\.net\\.");
	private static final Pattern SOURCE_REMOTE = Pattern.compile("\\b(URLClassLoader|InitialContext|InitialDirContext|Naming\\.lookup|Runtime\\.getRuntime\\(\\)\\.exec|ProcessBuilder|defineClass)\\b");

	public static List<LineHit> matchSource(String text) {
		List<LineHit> out = new ArrayList<>();
		if (text == null || text.isEmpty()) return out;
		String[] lines = text.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			String trimmed = line.trim();
			if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) continue;
			if (trimmed.startsWith("import ")) {
				// Imports: flag only network imports (red) so the user sees them at the top.
				String imported = trimmed.substring(7).replace(";", "").trim();
				Kind kind = classify(imported.replace('.', '/'));
				if (kind != null) out.add(new LineHit(i, kind, "import " + imported));
				continue;
			}
			Matcher m;
			if ((m = WEBHOOK_PATTERN.matcher(line)).find()) out.add(new LineHit(i, Kind.WEBHOOK, m.group()));
			else if ((m = SOURCE_REMOTE.matcher(line)).find()) out.add(new LineHit(i, Kind.REMOTE_CODE, m.group()));
			else if ((m = SOURCE_HTTP.matcher(line)).find()) out.add(new LineHit(i, Kind.HTTP, m.group().trim()));
			else if ((m = SOURCE_SOCKET.matcher(line)).find()) out.add(new LineHit(i, Kind.SOCKET, m.group().trim()));
			else if ((m = URL_PATTERN.matcher(line)).find()) out.add(new LineHit(i, Kind.URL_STRING, m.group()));
		}
		return out;
	}

	public static String lower(String s) {
		return s.toLowerCase(Locale.ROOT);
	}
}
