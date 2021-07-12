import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.Macro;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.ImageRoi;
import ij.macro.Interpreter;
import ij.measure.Calibration;
import ij.plugin.PlugIn;
import ij.process.ColorProcessor;
import ij.process.FHT;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.Calendar;

import com.github.sarxos.webcam.Webcam;
import com.github.sarxos.webcam.WebcamDiscoveryService;

/**
 * @author Jerome Mutterer
 * @date 2014 12 15 first release
 * @date 2015 01 14 added macro support and custom resolutions thanks to Jarom
 *       Jackson
 * @date 2015 02 03 added proper macro support using GenericDialog for options
 *       thanks to Wayne Rasband
 * @modif François Gannier
 * @date 2015 06 01 added calibration
 * @date 2015 06 01 added saving prefs
 * @date 2015 06 21 added fps on statusbar
 * @date 2015 06 24 fixed error with no webcam plugged
 * @date 2015 08 18 added acquiring timelapse sequences thanks to Chris Elliott
 * @date 2015 09 14 fixed a bug when timelapse would start despite of user
 *       setting
 * @date 2016 05 18 added an option to display live FFT spectrum with image
 *       inset
 */

public class IJ_webcam_plugin implements PlugIn {

	Webcam camera;
	BufferedImage image;
	ImagePlus imp, imp2, fft, outputImage;
	ImageProcessor ip;
	int camID = 0, width = 0, height = 0, interval = 1, nFrames = 1;
	float calib = 1;
	String unit = "pixel";
	private String macro;
	boolean grab = false, customSize = false, doTimelapse = false, shiftToStart = false, doFFT = false, doMacro = false;
	boolean displayFPS = true;
	private FHT fht;

	private GenericDialog gd;

	public void run(String s) {

		camera = Webcam.getDefault();
		if (null == camera) {
			IJ.log("No webcam detected");
			return;
		}
		Prefs.set("Cam.newImage", false);

		if (!showDialog())
			return;
		camera = Webcam.getWebcams().get(camID);

		if (null != camera) {
			Dimension[] sizes = camera.getViewSizes();
			Dimension s1 = sizes[sizes.length - 1];

			if (customSize && (width > 0) && (height > 0)) {
				Dimension[] customSizes = new Dimension[1];
				customSizes[0] = new Dimension(width, height);
				camera.setCustomViewSizes(customSizes);
				s1 = customSizes[0];
			}

			camera.setViewSize(s1);
			camera.open();
			ip = new ColorProcessor(s1.width, s1.height);
			imp = new ImagePlus("", ip);
			imp2 = new ImagePlus("tmp");

			Calibration cal = imp.getCalibration();
			cal.setUnit(unit);
			cal.pixelWidth = calib;
			cal.pixelHeight = calib;

			WindowManager.addWindow(imp.getWindow());

			imp.show();
			image = camera.getImage();

			long frames = 0;
			double frameRate;
			long currentTime, initialTime = Calendar.getInstance().getTimeInMillis(), diff, frameTime;
			frameTime = initialTime;
			String framerateString;
			frameTime = Calendar.getInstance().getTimeInMillis();
			String times = "<timestamps>";
			boolean timelapseFired = false;

			while (!(IJ.escapePressed() || null == imp.getWindow())) {
				if (camera.isImageNew()) {
					image = camera.getImage();
					if (doFFT) {
						imp2.setImage(image);
						ip = imp2.getProcessor();
						fht = new FHT(pad(ip));
						fht.setShowProgress(false);
						fht.transform();
						ImageProcessor ps = fht.getPowerSpectrum();
						imp.setProcessor(ps);
						imp.updateAndDraw();
						int size = imp.getWidth() / 4;
						ImageRoi roi = new ImageRoi(0, 0, ip.resize(size, size * ip.getHeight() / ip.getWidth()));
						imp.setOverlay(roi, Color.BLACK, 1, Color.BLACK);
						;

					} else {
						imp2.setImage(image);
						ip = imp2.getProcessor();
						if (imp.getNSlices() > 1) {
							imp.getImageStack().setProcessor(ip, imp.getNSlices());
							imp.setStack("Live (press Escape to finish, Space to add one frame)", imp.getImageStack());
						} else {
							if (doMacro) {
								if(IJ.escapePressed()) break; // to try and avoid the esc key interrupting the macro instead.
								if (runMacro(macro, imp2))
									ip = imp2.getProcessor();
									imp2.saveRoi();
							}
							imp.setProcessor(ip);
							if (doMacro) imp.restoreRoi();
							imp.updateAndDraw();
						}

						if (displayFPS) {
							frames++;
							currentTime = Calendar.getInstance().getTimeInMillis();
							diff = currentTime - initialTime;
							if (diff > 0) {
								frameRate = (double) frames * 1000;
								frameRate /= diff;
							} else
								frameRate = 0;
							framerateString = String.format("%.1f fps", frameRate);
							IJ.showStatus(framerateString);
						}
						imp.updateAndDraw();

						Prefs.set("Cam.newImage", true);

						if ((IJ.shiftKeyDown() && doTimelapse) || (!shiftToStart))
							timelapseFired = true;

						if (grab)
							break;

						if (IJ.spaceBarDown()
								|| (doTimelapse && timelapseFired && (frameTime + interval - Calendar.getInstance()
										.getTimeInMillis()) < 0) && (imp.getNSlices() < nFrames)) {
							ImageStack imageStack = imp.getImageStack();
							imageStack.addSlice("", new ColorProcessor(image));
							frameTime = Calendar.getInstance().getTimeInMillis();
							imp.setStack("Live (press Escape to finish, Space to add one frame)", imageStack);
							imp.setSlice(imp.getNSlices());
							times += "\n" + frameTime;

							imageStack = null;
							IJ.setKeyUp(KeyEvent.VK_SPACE);
							if (imp.getNSlices() == nFrames)
								grab = true;
						}
					}
				}
			}

			imp.setTitle("Snap");

			if (doFFT) {
				imp.setProcessor(ip);
				imp.setOverlay(null);
				IJ.run(imp, "FFT", "");
				IJ.run("Tile");

			}
			frameTime = Calendar.getInstance().getTimeInMillis();
			times += "\n" + frameTime;

			imp.setProperty("Info", times + "\n</timestamps>");
			camera.close();

			Prefs.set("Webcam.width", width);
			Prefs.set("Webcam.height", height);
			Prefs.set("Webcam.interval", interval);
			Prefs.set("Webcam.nFrames", nFrames);
			Prefs.set("Webcam.doTimelapse", doTimelapse);
			Prefs.set("Webcam.shiftToStart", shiftToStart);
			Prefs.set("Webcam.displayFPS", displayFPS);
			Prefs.set("Webcam.customSize", customSize);
			Prefs.set("Webcam.doMacro", doMacro);
			Prefs.set("Webcam.macro", macro);

			cal = imp.getCalibration();

			if ((cal.pixelWidth != calib) || (cal.getUnit() != unit)) {
				if (IJ.showMessageWithCancel("Preferences", "Calibration changed, replace?")) {
					Prefs.set("Webcam.calib", cal.pixelWidth);
					Prefs.set("Webcam.calUnit", cal.getUnit());

				}
			} else {
				Prefs.set("Webcam.calib", calib);
				Prefs.set("Webcam.calUnit", unit);
			}

		}
	}

	boolean showDialog() {
		int n = 0;
		String[] cameraNames = new String[Webcam.getWebcams().size()];

		for (Webcam c : Webcam.getWebcams()) {
			cameraNames[n] = c.getName();
			n++;
		}

		customSize = (boolean) Prefs.get("Webcam.customSize", false);
		width = (int) Prefs.get("Webcam.width", width);
		height = (int) Prefs.get("Webcam.height", height);
		calib = (float) Prefs.get("Webcam.calib", 1.0);
		unit = (String) Prefs.get("Webcam.calUnit", "\u00B5m");
		macro = (String) Prefs.get("Webcam.macro", "run('8-bit Color', 'number=4');");
		displayFPS = (boolean) Prefs.get("Webcam.displayFPS", displayFPS);
		shiftToStart = (boolean) Prefs.get("Webcam.shiftToStart", false);
		doTimelapse = (boolean) Prefs.get("Webcam.doTimelapse", false);
		doFFT = (boolean) Prefs.get("Webcam.doFFT", false);
		doMacro = (boolean) Prefs.get("Webcam.doMacro", false);
		interval = (int) Prefs.get("Webcam.interval", interval);
		nFrames = (int) Prefs.get("Webcam.nFrames", nFrames);

		gd = new GenericDialog("IJ webcam plugin...");
		gd.addChoice("Camera name", cameraNames, cameraNames[0]);
		gd.addCheckbox("Show FPS in status bar", displayFPS);
		gd.addCheckbox("Grab and return", false);
		gd.addCheckbox("Custom size", customSize);
		gd.addNumericField("Width", width, 0, 5, "pixels");
		gd.addNumericField("Height", height, 0, 5, "pixels");
		gd.addMessage("\nCalibration");
		gd.addStringField("Unit", unit);
		gd.addNumericField("Pixel_size", calib, 8, 12, "units/px");
		gd.addMessage("\nTimelapse");

		gd.addCheckbox("Do timelapse", doTimelapse);
		gd.addCheckbox("Press_Shift to start", shiftToStart);

		gd.addNumericField("Interval", interval, 0, 6, "msecs");
		gd.addNumericField("Frames", nFrames, 0, 6, "");
		gd.addCheckbox("Live FFT instead of image", doFFT);
		gd.addCheckbox("Process Live Image", doMacro);
		gd.addTextAreas(macro,null, 5, 40);

		gd.showDialog();
		if (gd.wasCanceled())
			return false;
		camID = (int) gd.getNextChoiceIndex();
		displayFPS = gd.getNextBoolean();
		grab = (boolean) gd.getNextBoolean();
		customSize = (boolean) gd.getNextBoolean();
		width = (int) gd.getNextNumber();
		height = (int) gd.getNextNumber();
		unit = (String) gd.getNextString();
		calib = (float) gd.getNextNumber();
		doTimelapse = gd.getNextBoolean();
		shiftToStart = gd.getNextBoolean();
		interval = (int) gd.getNextNumber();
		nFrames = (int) gd.getNextNumber();
		doFFT = gd.getNextBoolean();
		doMacro = gd.getNextBoolean();
		macro = gd.getTextArea1().getText();
		return true;
	}

	// taken from the FFT.java class
	ImageProcessor pad(ImageProcessor ip) {
		int originalWidth = ip.getWidth();
		int originalHeight = ip.getHeight();
		int maxN = Math.max(originalWidth, originalHeight);
		int i = 2;
		while (i < maxN)
			i *= 2;
		if (i == maxN && originalWidth == originalHeight) {
			return ip;
		}
		maxN = i;
		ImageStatistics stats = ImageStatistics.getStatistics(ip, ImageStatistics.MEAN, null);
		ImageProcessor ip2 = ip.createProcessor(maxN, maxN);
		ip2.setValue(stats.mean);
		ip2.fill();
		ip2.insert(ip, 0, 0);
		return ip2;
	}
	
	// taken from the batch processor
	private boolean runMacro(String macro, ImagePlus imp) {
		WindowManager.setTempCurrentImage(imp);
		Interpreter interp = new Interpreter();
		try {
			outputImage = interp.runBatchMacro(macro, imp);
		} catch(Throwable e) {
			interp.abortMacro();
			String msg = e.getMessage();
			if (!(e instanceof RuntimeException && msg!=null && e.getMessage().equals(Macro.MACRO_CANCELED)))
				IJ.handleException(e);
			return false;
		} finally {
			WindowManager.setTempCurrentImage(null);
		}
		return true;
	}

}
