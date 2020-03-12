package com.vir.service.impl.processor;

import static org.bytedeco.javacpp.lept.pixDestroy;
import static org.bytedeco.javacpp.lept.pixReadMem;

import java.awt.image.BufferedImage;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Calendar;
import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Stopwatch;
import com.vir.exception.UnparseableContentException;
import com.vir.helpers.IOHelper;
import com.vir.helpers.OcrReader;
import com.vir.helpers.TesseractOcrReader;
import com.vir.model.FileType;
import com.vir.model.Text;
import com.vir.model.ThreadValue;
import com.vir.model.enumerations.ImageConversionOption;
import com.vir.service.EmailService;
import com.vir.service.FileProcessorService;
import com.vir.service.TextProcessorService;
import com.vir.service.impl.OcrOptimizerService;

import org.apache.http.util.TextUtils;
import org.apache.tika.io.TikaInputStream;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.lept.PIX;
import org.bytedeco.javacpp.tesseract.TessBaseAPI;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.amazonaws.services.textract.AmazonTextract;
import com.amazonaws.services.textract.AmazonTextractClientBuilder;
import com.amazonaws.services.textract.model.*;
import com.amazonaws.util.IOUtils;
import java.nio.ByteBuffer;
import java.util.List;

import java.time.Month;
import java.time.LocalDate;

@Service("imgProcessorService")
public class ImgProcessorService implements FileProcessorService
{
	private final TextProcessorService textProcessorService;
	private final EmailService emailService;
	private final boolean isProduction;
	private Month latestMonth;
	private int textractCounter;
	public ImgProcessorService
	(
		@Qualifier("optimizedTextProcessorService")
		TextProcessorService textProcessorService
		, EmailService emailService
	)
	{
		this.textProcessorService = textProcessorService;
		this.emailService = emailService;
		String prodEnvironmentVariable = System.getenv("PROD");
		this.isProduction = "1".equals(prodEnvironmentVariable);
		this.latestMonth = LocalDate.now().getMonth();
		this.textractCounter = 0;
	}
	private Text getProcessedString(String s)
	{
		try
		{
			Text text = this.textProcessorService.process(s);
			return text;
		}//try
		catch(Throwable t)
		{
			this.emailService.sendExceptionEmail(t);
			return null;
		}//catch
	}
	@Override
	public Text process(MultipartFile file, FileType type) throws UnparseableContentException
	{
		if(file == null) return null;

		System.gc();

		String fileName = file.getOriginalFilename();
		int dotIndex = TextUtils.isBlank(fileName) ? -1 : fileName.lastIndexOf('.');
		//if there's no extension, let's try jpg. If it doesn't work it doesn't hurt to try
		String fileExtension = dotIndex == -1 ? "jpg" : fileName.substring(dotIndex + 1);

		Text result = this.createTextFromFile(file, fileExtension);

		return result;
	}
	//this is used in non-production for viewing the amount of time something takes. The JIT will eliminate this (and the corresponding methods) in production builds
	Stopwatch sw;
	private void startStopWatch()
	{
		if(this.isProduction) return;
		sw = Stopwatch.createStarted();
	}
	private enum StopWatchOutputOptions
	{
		TIME,
		NO_TIME
	}
	private void printStopWatchMessage(String msg, StopWatchOutputOptions options)
	{
		if(this.isProduction) return;
		boolean showTime = options == StopWatchOutputOptions.TIME;
		System.out.println(msg + (showTime ? sw : ""));
	}
	private void resetAndRestartStopWatch()
	{
		if(this.isProduction || sw == null) return;
		if(sw.isRunning()) sw.reset();
		sw.start();
	}
	private Text createTextFromFile(MultipartFile file, String fileExtension) throws UnparseableContentException
	{
		try
		(
			InputStream stream = file.getInputStream();
			TikaInputStream tikaStream = TikaInputStream.get(stream);
			InputStream imageByteStream = file.getInputStream();
		)
		{	
			this.startStopWatch();
			this.printStopWatchMessage("Start converting images", StopWatchOutputOptions.NO_TIME);
			OcrOptimizerService ocrOptimizerService = new OcrOptimizerService();
			EnumSet<ImageConversionOption> options = EnumSet.of(ImageConversionOption.RAW);
			//first get the raw image
			BufferedImage imgRaw = ocrOptimizerService.preprocessImage(tikaStream, options, fileExtension);

			options = EnumSet.of(ImageConversionOption.REMOVE_NOISE);
			//finally get the image in gray scale and remove noise
			BufferedImage imgNoiseRemoval = ocrOptimizerService.preprocessImage(imgRaw, options, fileExtension);

			ocrOptimizerService = null;
			System.gc();


			//this.printStopWatchMessage("Finished converting images. It took ", StopWatchOutputOptions.TIME);

			this.resetAndRestartStopWatch();
			//this.printStopWatchMessage("Begin tesseract allocating.", StopWatchOutputOptions.NO_TIME);
			//the tesseract ocr reader should NEVER be used as service and injected for the lifetime of the application
			//this is due to the fact that it uses native resources and it could lead to memory leaks if NOT handled properly
			//please note also that this could easily be switched to something else either as a replacement or for comparison
			//that's the reason for creating the interface

			if(textractCounter > 1000 && this.latestMonth == LocalDate.now().getMonth())
			{
				System.out.println("Error test");
				throw new UnparseableContentException("Textract uses exceeded");
			}
			else if(this.latestMonth != LocalDate.now().getMonth())
			{
				this.textractCounter = 0;
				this.latestMonth = LocalDate.now().getMonth();
			}

			AmazonTextract client = AmazonTextractClientBuilder.defaultClient();

			ByteBuffer imageBytes = null;
			imageBytes = ByteBuffer.wrap(IOUtils.toByteArray(imageByteStream));

			DetectDocumentTextRequest request = new DetectDocumentTextRequest().withDocument(new Document().withBytes(imageBytes));
			textractCounter++;			

			DetectDocumentTextResult textractResult = client.detectDocumentText(request);
			List<Block> blocks = textractResult.getBlocks();

			String awsStringResult = "";

			for (Block block : blocks) {
				if ((block.getBlockType()).equals("LINE")) {
							awsStringResult = awsStringResult + " \n" + block.getText();
				}
			}

			
			System.out.println(awsStringResult);
			Text awsTextResult = this.getProcessedString(awsStringResult);

			return awsTextResult;
			//TESSERACT
			/*
			String tessdataPath = System.getenv("TESSDATA_PREFIX") + "\\tessdata";
			try(OcrReader tesseractOcrReader = new TesseractOcrReader(tessdataPath, "eng"))
			{
				this.printStopWatchMessage("Finished tesseract allocating. It took ", StopWatchOutputOptions.TIME);

				this.printStopWatchMessage("Begin reading first image.", StopWatchOutputOptions.NO_TIME);
				this.resetAndRestartStopWatch();

				String result = tesseractOcrReader.getOcrResult(imgRaw, fileExtension);
				imgRaw = null;
				System.gc();
				
				this.printStopWatchMessage("Finished reading first image. It took ", StopWatchOutputOptions.TIME);

				Text textResult = TextUtils.isBlank(result) ? null : this.getProcessedString(result);

				if(textResult == null) textResult = new Text();
				
				long wordCount = textResult.getTotalWithCategories();

				System.out.println("First word count: " + wordCount);

				this.resetAndRestartStopWatch();
				this.printStopWatchMessage("Begin reading second image.", StopWatchOutputOptions.NO_TIME);
				String result2 = tesseractOcrReader.getOcrResult(imgNoiseRemoval, fileExtension);
				imgNoiseRemoval = null;
				System.gc();
				
				this.printStopWatchMessage("Finished reading second image. It took ", StopWatchOutputOptions.TIME);

				Text textResult2 = TextUtils.isBlank(result2) ? null : this.getProcessedString(result2);

				if(textResult2 == null) textResult2 = new Text();

				long wordCount2 = textResult2.getTotalWithCategories();

				System.out.println("Second word count: " + wordCount2);

				//in the case that there are no words from the datastore, let's see who got the most alphanumeric characters
				if(wordCount2 == 0 && wordCount == 0)
				{
					System.out.println("Both results return 0 words from the datastore. Attempting to see which one was able read the most alphanumeric characters.");
					wordCount = this.countAlphaNumeric(result);
					wordCount2 = this.countAlphaNumeric(result2);
					System.out.println("First character count: " + wordCount);
					System.out.println("Second character count: " + wordCount2);
				}//if

				boolean secondsBetter = wordCount2 > wordCount;
				boolean allZeros = wordCount2 == 0 && wordCount == 0;
				System.out.println
					(
						String.format
						(
							"The best results comes from %s"
							, allZeros
								? "NONE -> both results are 0"
								: String.format
									(
										"the %s version"
										, secondsBetter
											? "second"
											: "first"
									)
						)
					);

				if(allZeros)
				{
					imgRaw = null;
					imgNoiseRemoval = null;
					textResult = null;
					textResult2 = null;
					result = null;
					result2 = null;
					System.gc();
					throw new UnparseableContentException("Could not parse the file");
				}//if

				//if they're both the same, then we favo the first over the second
				Text bestResult = secondsBetter ? textResult2 : textResult;

				//cleaning up. There has been some memory leaks, so to ensure that the GC cleans up properly we're setting these to null
				imgRaw = null;
				imgNoiseRemoval = null;
				textResult = null;
				textResult2 = null;
				result = null;
				result2 = null;
				System.gc();

				return awsTextResult;
			}//try
			*/
		}//try
		catch(UnparseableContentException ex)
		{
			System.err.println(ex);
			this.emailService.sendExceptionEmail(ex);
			throw ex;
		}
		catch(Throwable t)
		{
			System.err.println(t);
			this.emailService.sendExceptionEmail(t);
			return new Text();
		}//catch
	}
	private long countAlphaNumeric(String result)
	{
		if(TextUtils.isBlank(result)) return 0;
		long count = 0;
		for(char c : result.toCharArray())
		{
			if(!Character.isLetterOrDigit(c)) continue;
			++count;
		}//for c
		return count;
	}
}
