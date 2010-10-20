package org.dspace.app.mediafilter;

/*
 * $HeadURL: $
 *
 * Version: $Revision: $
 *
 * Date: $Date: $
 *
 * Copyright (c) 2002-2009, The DSpace Foundation.  All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * - Redistributions of source code must retain the above copyright
 * notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above copyright
 * notice, this list of conditions and the following disclaimer in the
 * documentation and/or other materials provided with the distribution.
 *
 * - Neither the name of the DSpace Foundation nor the names of its
 * contributors may be used to endorse or promote products derived from
 * this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * ``AS IS'' AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
 * A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
 * HOLDERS OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR
 * TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE
 * USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH
 * DAMAGE.
 */

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import org.apache.log4j.Logger;
import org.dspace.core.ConfigurationManager;
import org.dspace.core.Utils;

/**
 * Thumbnail MediaFilter for PDF sources
 *
 * This filter generates thumbnail images for PDF documents, _including_
 * 3D PDF documents with 2D "poster" images.  Since the PDFBox library
 * does not understand these, and fails to render a lot of other PDFs,
 * this filter forks a process running the "pdftoppm" program from the
 * XPdf suite -- see http://www.foolabs.com/xpdf/
 * This is a suite of open-source PDF tools that has been widely ported
 * to Unix platforms and the ones we use (pdftoppm, pdfinfo) even
 * run on Win32.
 *
 * This was written for the FACADE project but it is not directly connected
 * to any of the other FACADE-specific software.  The FACADE UI expects
 * to find thumbnail images for 3D PDFs generated by this filter.
 *
 * Requires DSpace config properties keys:
 *
 *  xpdf.path.pdftoppm -- absolute path to "pdftoppm" executable (required!)
 *  xpdf.path.pdfinfo -- absolute path to "pdfinfo" executable (required!)
 *  thumbnail.maxwidth  -- borrowed from thumbnails, max dim of generated image
 *
 * @author Larry Stone
 * @see org.dspace.app.mediafilter.MediaFilter
 */
public class XPDF2Thumbnail extends MediaFilter
{
    private static Logger log = Logger.getLogger(XPDF2Thumbnail.class);

    // maximum size of either preview image dimension
    private static final int MAX_PX = 800;

    // maxium DPI - use common screen res, 100dpi.
    private static final int MAX_DPI = 100;

    // command to get image from PDF; @FILE@, @OUTPUT@ are placeholders
    private static final String XPDF_PDFTOPPM_COMMAND[] =
    {
        "@COMMAND@", "-q", "-f", "1", "-l", "1",
        "-r", "@DPI@", "@FILE@", "@OUTPUTFILE@"
    };

    // command to get image from PDF; @FILE@, @OUTPUT@ are placeholders
    private static final String XPDF_PDFINFO_COMMAND[] =
    {
        "@COMMAND@", "-f", "1", "-l", "1", "-box", "@FILE@"
    };

    // executable path for "pdftoppm", comes from DSpace config at runtime.
    private String pdftoppmPath = null;

    // executable path for "pdfinfo", comes from DSpace config at runtime.
    private String pdfinfoPath = null;

    // match line in pdfinfo output that describes file's MediaBox
    private static final Pattern MEDIABOX_PATT = Pattern.compile(
        "^Page\\s+\\d+\\s+MediaBox:\\s+([\\.\\d-]+)\\s+([\\.\\d-]+)\\s+([\\.\\d-]+)\\s+([\\.\\d-]+)");

    // also from thumbnail.maxwidth in config
    private int maxwidth = 0;

    // backup default for size, on the large side.
    private static final int DEFAULT_MAXWIDTH = 500;

    public String getFilteredName(String oldFilename)
    {
        return oldFilename + ".jpg";
    }

    public String getBundleName()
    {
        return "THUMBNAIL";
    }

    public String getFormatString()
    {
        return "JPEG";
    }

    public String getDescription()
    {
        return "Generated Thumbnail";
    }

    // canonical MediaFilter method to generate the thumbnail as stream.
    public InputStream getDestinationStream(InputStream sourceStream)
            throws Exception
    {
        // sanity check: xpdf paths are required. can cache since it won't change
        if (pdftoppmPath == null || pdfinfoPath == null)
        {
            pdftoppmPath = ConfigurationManager.getProperty("xpdf.path.pdftoppm");
            pdfinfoPath = ConfigurationManager.getProperty("xpdf.path.pdfinfo");
            if (pdftoppmPath == null)
                throw new IllegalStateException("No value for key \"xpdf.path.pdftoppm\" in DSpace configuration!  Should be path to XPDF pdftoppm executable.");
            if (pdfinfoPath == null)
                throw new IllegalStateException("No value for key \"xpdf.path.pdfinfo\" in DSpace configuration!  Should be path to XPDF pdfinfo executable.");
            maxwidth = ConfigurationManager.getIntProperty("thumbnail.maxwidth");
            if (maxwidth == 0)
                maxwidth = DEFAULT_MAXWIDTH;
        }

        // make local file copy of source PDF since the PDF tools
        // require a file for random access.
        // XXX fixme would be nice to optimize this if we ever get
        // XXX  a DSpace method to access (optionally!) the _file_ of
        //     a Bitstream in the asset store, only when there is one of course.
        File sourceTmp = File.createTempFile("DSfilt",".pdf");
        sourceTmp.deleteOnExit();
        int status = 0;
        BufferedImage source = null;
        try
        {
            OutputStream sto = new FileOutputStream(sourceTmp);
            Utils.copy(sourceStream, sto);
            sto.close();
            sourceStream.close();

            // First get max physical dim of bounding box of first page
            // to compute the DPI to ask for..  otherwise some AutoCAD
            // drawings can produce enormous files even at 75dpi, for
            // 48" drawings..

            // run pdfinfo, look for MediaBox description in the output, e.g.
            // "Page    1 MediaBox:     0.00     0.00   612.00   792.00"
            //
            int dpi = 0;
            String pdfinfoCmd[] = XPDF_PDFINFO_COMMAND.clone();
            pdfinfoCmd[0] = pdfinfoPath;
            pdfinfoCmd[pdfinfoCmd.length-1] = sourceTmp.toString();
            try
            {
                MatchResult mediaBox = null;
                Process pdfProc = Runtime.getRuntime().exec(pdfinfoCmd);
                BufferedReader lr = new BufferedReader(new InputStreamReader(pdfProc.getInputStream()));
                String line;
                for (line = lr.readLine(); line != null; line = lr.readLine())
                {
                    // if (line.matches(MEDIABOX_PATT))
                    Matcher mm = MEDIABOX_PATT.matcher(line);
                    if (mm.matches())
                        mediaBox = mm.toMatchResult();
                }
                int istatus = pdfProc.waitFor();
                if (istatus != 0)
                    log.error("XPDF pdfinfo proc failed, exit status="+istatus+", file="+sourceTmp);
                if (mediaBox == null)
                {
                    log.error("Sanity check: Did not find \"MediaBox\" line in output of XPDF pdfinfo, file="+sourceTmp);
                    throw new IllegalArgumentException("Failed to get MediaBox of PDF with pdfinfo, cannot compute thumbnail.");
                }
                else
                {
                    float x0 = Float.parseFloat(mediaBox.group(1));
                    float y0 = Float.parseFloat(mediaBox.group(2));
                    float x1 = Float.parseFloat(mediaBox.group(3));
                    float y1 = Float.parseFloat(mediaBox.group(4));
                    int maxdim = (int)Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
                    dpi = Math.min(MAX_DPI, (MAX_PX * 72 / maxdim));
                    log.debug("DPI: pdfinfo method got dpi="+dpi+" for max dim="+maxdim+" (points, 1/72\")");
                }
            }
            catch (InterruptedException e)
            {
                log.error("Failed transforming file for preview: ",e);
                throw new IllegalArgumentException("Failed transforming file for thumbnail: ",e);
            }
            catch (NumberFormatException e)
            {
                log.error("Failed interpreting pdfinfo results, check regexp: ",e);
                throw new IllegalArgumentException("Failed transforming file for thumbnail: ",e);
            }

            // Render page 1 using xpdf's pdftoppm
            // Requires Sun JAI imageio additions to read ppm directly.
            // this will get "-000001.ppm" appended to it by pdftoppm
            File outPrefixF = File.createTempFile("prevu","out");
            String outPrefix = outPrefixF.toString();
            outPrefixF.delete();
            String pdfCmd[] = XPDF_PDFTOPPM_COMMAND.clone();
            pdfCmd[0] = pdftoppmPath;
            pdfCmd[pdfCmd.length-3] = String.valueOf(dpi);
            pdfCmd[pdfCmd.length-2] = sourceTmp.toString();
            pdfCmd[pdfCmd.length-1] = outPrefix;
            File outf = new File(outPrefix+"-000001.ppm");
            log.debug("Running xpdf command: "+Arrays.deepToString(pdfCmd));
            try
            {
                Process pdfProc = Runtime.getRuntime().exec(pdfCmd);
                status = pdfProc.waitFor();
                log.debug("PDFTOPPM output is: "+outf+", exists="+outf.exists());
                source = ImageIO.read(outf);
            }
            catch (InterruptedException e)
            {
                log.error("Failed transforming file for preview: ",e);
                throw new IllegalArgumentException("Failed transforming file for preview: ",e);
            }
            finally
            {
                outf.delete();
            }
        }
        finally
        {
            sourceTmp.delete();
            if (status != 0)
                log.error("PDF conversion proc failed, exit status="+status+", file="+sourceTmp);
        }

        if (source == null)
            throw new IOException("Unknown failure while transforming file to preview: no image produced.");

        // Scale image and return in-memory stream
        BufferedImage toenail = scaleImage(source, maxwidth*3/4, maxwidth);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(toenail, "jpeg", baos);
        return new ByteArrayInputStream(baos.toByteArray());
    }

    // scale the image, preserving aspect ratio, if at least one
    // dimension is not between min and max.
    private static BufferedImage scaleImage(BufferedImage source,
                                            int min, int max)
    {
        int xsize = source.getWidth(null);
        int ysize = source.getHeight(null);
        int msize = Math.max(xsize, ysize);
        BufferedImage result = null;

        // scale the image if it's outside of requested range.
        // ALSO pass through if min and max are both 0
        if ((min == 0 && max == 0) ||
            (msize >= min && Math.min(xsize, ysize) <= max))
            return source;
        else
        {
            int xnew = xsize * max / msize;
            int ynew = ysize * max / msize;
            result = new BufferedImage(xnew, ynew, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2d = result.createGraphics();
            g2d.drawImage(source, 0, 0, xnew, ynew, null);
            return result;
        }
    }
}

 	  	 
