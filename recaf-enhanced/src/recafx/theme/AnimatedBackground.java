package recafx.theme;

import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

import java.util.Random;

/**
 * Canvas drawn behind the main window content. Renders at ~30 FPS and only while {@link #setActive} is true.
 */
public class AnimatedBackground extends Canvas {
	private static final int MAX_FPS = 30;
	private final Random random = new Random(42);
	private ThemeConfig.Background mode = ThemeConfig.Background.NONE;
	private Color colorA = Color.web("#1b1f2a"), colorB = Color.web("#2c2c2c"), accent = Color.web("#0a84ff");
	private double speed = 1.0;
	private double density = 1.0;
	private boolean active = true;
	private long lastFrame;
	private double time;
	private double[] px, py, pz, pv, pw;
	private int[] matrixHeads;
	private double[] matrixSpeeds;
	private int particleCount = -1;
	private final AnimationTimer timer = new AnimationTimer() {
		@Override
		public void handle(long now) {
			if (!active || getWidth() <= 0 || getHeight() <= 0) return;
			if (now - lastFrame < 1_000_000_000L / MAX_FPS) return;
			double dt = lastFrame == 0 ? 1.0 / MAX_FPS : Math.min(0.1, (now - lastFrame) / 1e9);
			lastFrame = now;
			time += dt * speed;
			draw();
		}
	};

	public AnimatedBackground() {
		setMouseTransparent(true);
		widthProperty().addListener((ob, old, cur) -> {
			particleCount = -1;
			redrawStatic();
		});
		heightProperty().addListener((ob, old, cur) -> {
			particleCount = -1;
			redrawStatic();
		});
	}

	public void configure(ThemeConfig.Background mode, Color a, Color b, Color accent, double speed, double density, int blur) {
		this.mode = mode;
		this.colorA = a;
		this.colorB = b;
		this.accent = accent;
		this.speed = Math.max(0, Math.min(3, speed));
		this.density = Math.max(0.2, Math.min(2.5, density));
		this.particleCount = -1;
		setEffect(blur > 0 ? new GaussianBlur(Math.min(30, blur)) : null);
		if (mode.animated() && active && this.speed > 0) {
			lastFrame = 0;
			timer.start();
		} else {
			timer.stop();
		}
		redrawStatic();
	}

	public void setActive(boolean active) {
		this.active = active;
		if (active && mode.animated() && speed > 0) { lastFrame = 0; timer.start(); }
		else timer.stop();
	}

	public void dispose() {
		timer.stop();
	}

	private void redrawStatic() {
		if (getWidth() <= 0 || getHeight() <= 0) return;
		draw();
	}

	private void draw() {
		GraphicsContext g = getGraphicsContext2D();
		double w = getWidth(), h = getHeight();
		g.clearRect(0, 0, w, h);
		if (mode == ThemeConfig.Background.NONE) {
			g.setFill(colorB);
			g.fillRect(0, 0, w, h);
			return;
		}
		g.setFill(new LinearGradient(0, 0, w, h, false, CycleMethod.NO_CYCLE, new Stop(0, colorA), new Stop(1, colorB)));
		g.fillRect(0, 0, w, h);
		switch (mode) {
			case STARFIELD -> drawStars(g, w, h);
			case AURORA -> drawAurora(g, w, h);
			case PARTICLES -> drawParticles(g, w, h);
			case WAVES -> drawWaves(g, w, h);
			case MATRIX -> drawMatrix(g, w, h);
			case NEBULA -> drawNebula(g, w, h);
			default -> {}
		}
	}

	private void ensureParticles(int count) {
		if (particleCount == count && px != null) return;
		particleCount = count;
		px = new double[count];
		py = new double[count];
		pz = new double[count];
		pv = new double[count];
		pw = new double[count];
		for (int i = 0; i < count; i++) {
			px[i] = random.nextDouble();
			py[i] = random.nextDouble();
			pz[i] = 0.2 + random.nextDouble() * 0.8;
			pv[i] = random.nextDouble() * Math.PI * 2;
			pw[i] = 0.5 + random.nextDouble();
		}
	}

	private void drawStars(GraphicsContext g, double w, double h) {
		int count = (int) (Math.min(600, (w * h) / 4500) * density);
		ensureParticles(Math.max(40, count));
		for (int i = 0; i < particleCount; i++) {
			double drift = (time * 0.01 * pz[i]) % 1.0;
			double x = ((px[i] + drift) % 1.0) * w;
			double y = py[i] * h;
			double twinkle = 0.55 + 0.45 * Math.sin(time * 1.7 * pw[i] + pv[i]);
			double size = 0.6 + pz[i] * 1.8 * twinkle;
			g.setFill(Color.color(1, 1, 1, 0.35 + 0.6 * twinkle * pz[i]));
			g.fillOval(x, y, size, size);
		}
		// A couple of soft accent glows.
		for (int i = 0; i < 3; i++) {
			double cx = w * (0.2 + 0.3 * i) + Math.sin(time * 0.2 + i) * w * 0.05;
			double cy = h * (0.3 + 0.2 * i) + Math.cos(time * 0.15 + i) * h * 0.05;
			double r = Math.min(w, h) * 0.35;
			g.setFill(new RadialGradient(0, 0, cx, cy, r, false, CycleMethod.NO_CYCLE,
					new Stop(0, accent.deriveColor(0, 1, 1, 0.10)), new Stop(1, Color.TRANSPARENT)));
			g.fillOval(cx - r, cy - r, r * 2, r * 2);
		}
	}

	private void drawAurora(GraphicsContext g, double w, double h) {
		int bands = (int) (5 * density);
		for (int b = 0; b < Math.max(3, bands); b++) {
			double phase = time * 0.35 + b * 1.3;
			Color c = b % 2 == 0 ? accent : accent.deriveColor(60 + b * 20, 1, 1.1, 1);
			g.setFill(c.deriveColor(0, 1, 1, 0.07));
			g.beginPath();
			g.moveTo(0, h);
			int steps = 48;
			for (int s = 0; s <= steps; s++) {
				double t = (double) s / steps;
				double x = t * w;
				double y = h * (0.25 + 0.12 * b) + Math.sin(t * 4 + phase) * h * 0.08 + Math.sin(t * 9 - phase * 0.7) * h * 0.03;
				g.lineTo(x, y);
			}
			g.lineTo(w, h);
			g.closePath();
			g.fill();
		}
		// Glow near the top.
		g.setFill(new LinearGradient(0, 0, 0, h * 0.6, false, CycleMethod.NO_CYCLE,
				new Stop(0, accent.deriveColor(0, 1, 1, 0.18)), new Stop(1, Color.TRANSPARENT)));
		g.fillRect(0, 0, w, h * 0.6);
	}

	private void drawParticles(GraphicsContext g, double w, double h) {
		int count = (int) (Math.min(160, (w * h) / 14000) * density);
		ensureParticles(Math.max(20, count));
		double[] xs = new double[particleCount], ys = new double[particleCount];
		for (int i = 0; i < particleCount; i++) {
			xs[i] = (px[i] * w + Math.sin(time * 0.3 * pw[i] + pv[i]) * 40 + time * 6 * pz[i]) % (w + 20);
			ys[i] = (py[i] * h + Math.cos(time * 0.25 * pw[i] + pv[i]) * 30) % (h + 20);
			if (xs[i] < -20) xs[i] += w + 20;
			if (ys[i] < -20) ys[i] += h + 20;
		}
		g.setLineWidth(1);
		for (int i = 0; i < particleCount; i++) {
			for (int j = i + 1; j < particleCount; j++) {
				double dx = xs[i] - xs[j], dy = ys[i] - ys[j];
				double d2 = dx * dx + dy * dy;
				if (d2 < 120 * 120) {
					g.setStroke(accent.deriveColor(0, 1, 1, 0.25 * (1 - Math.sqrt(d2) / 120)));
					g.strokeLine(xs[i], ys[i], xs[j], ys[j]);
				}
			}
		}
		for (int i = 0; i < particleCount; i++) {
			double size = 2 + pz[i] * 3;
			g.setFill(accent.deriveColor(0, 1, 1.2, 0.8));
			g.fillOval(xs[i] - size / 2, ys[i] - size / 2, size, size);
		}
	}

	private void drawWaves(GraphicsContext g, double w, double h) {
		int layers = (int) Math.max(2, 4 * density);
		for (int l = 0; l < layers; l++) {
			double amp = h * (0.03 + 0.02 * l);
			double base = h * (0.55 + 0.1 * l);
			double phase = time * (0.6 + 0.2 * l) + l;
			g.setFill(accent.deriveColor(l * 12, 1, 1 - l * 0.12, 0.12));
			g.beginPath();
			g.moveTo(0, h);
			int steps = 64;
			for (int s = 0; s <= steps; s++) {
				double t = (double) s / steps;
				double y = base + Math.sin(t * 6 + phase) * amp + Math.sin(t * 13 - phase * 1.3) * amp * 0.4;
				g.lineTo(t * w, y);
			}
			g.lineTo(w, h);
			g.closePath();
			g.fill();
		}
	}

	private void drawMatrix(GraphicsContext g, double w, double h) {
		int columns = Math.max(8, (int) (w / 16 * density));
		if (matrixHeads == null || matrixHeads.length != columns) {
			matrixHeads = new int[columns];
			matrixSpeeds = new double[columns];
			for (int i = 0; i < columns; i++) {
				matrixHeads[i] = random.nextInt(60);
				matrixSpeeds[i] = 4 + random.nextDouble() * 10;
			}
		}
		double cellW = w / columns;
		double cellH = 16;
		int rows = (int) (h / cellH) + 1;
		g.setFont(javafx.scene.text.Font.font("JetBrains Mono", 13));
		for (int c = 0; c < columns; c++) {
			double head = (matrixHeads[c] + time * matrixSpeeds[c]) % (rows + 30);
			for (int r = 0; r < rows; r++) {
				double dist = head - r;
				if (dist < 0 || dist > 22) continue;
				double alpha = Math.max(0, 1 - dist / 22);
				char ch = (char) (0x30A0 + ((r * 31 + c * 17 + (int) (time * 3)) % 96));
				g.setFill(dist < 1 ? Color.color(0.85, 1, 0.9, 0.9) : accent.deriveColor(0, 1, 1, alpha * 0.55));
				g.fillText(String.valueOf(ch), c * cellW + 2, r * cellH + 12);
			}
		}
	}

	private void drawNebula(GraphicsContext g, double w, double h) {
		int blobs = (int) Math.max(3, 6 * density);
		for (int i = 0; i < blobs; i++) {
			double cx = w * (0.5 + 0.42 * Math.sin(time * 0.11 + i * 1.7));
			double cy = h * (0.5 + 0.38 * Math.cos(time * 0.09 + i * 2.3));
			double r = Math.min(w, h) * (0.25 + 0.1 * (i % 3));
			Color c = i % 2 == 0 ? accent : accent.deriveColor(80 + i * 15, 1, 1, 1);
			g.setFill(new RadialGradient(0, 0, cx, cy, r, false, CycleMethod.NO_CYCLE,
					new Stop(0, c.deriveColor(0, 1, 1, 0.22)), new Stop(0.6, c.deriveColor(0, 1, 1, 0.07)), new Stop(1, Color.TRANSPARENT)));
			g.fillOval(cx - r, cy - r, r * 2, r * 2);
		}
		drawStars(g, w, h);
	}

	@Override
	public boolean isResizable() {return true;}

	@Override
	public double prefWidth(double height) {return 0;}

	@Override
	public double prefHeight(double width) {return 0;}
}
