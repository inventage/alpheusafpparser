/*
Copyright 2015 Rudolf Fiala

This file is part of Alpheus AFP Parser.

Alpheus AFP Parser is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Alpheus AFP Parser is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with Alpheus AFP Parser.  If not, see <http://www.gnu.org/licenses/>
*/
package com.mgz.afp.parser;

import com.mgz.afp.base.StructuredField;
import com.mgz.afp.base.StructuredFieldIntroducer;
import com.mgz.afp.enums.SFFlag;
import com.mgz.afp.enums.SFTypeID;
import com.mgz.afp.exceptions.AFPParserException;
import com.mgz.afp.exceptions.UncheckedAFPException;
import com.mgz.afp.ptoca.PTX_PresentationTextData;
import com.mgz.afp.ptoca.controlSequence.PTOCAControlSequence;

import java.io.*;
import java.util.*;

import static com.mgz.afp.enums.SFFlag.hasExtension;
import static com.mgz.afp.parser.AFPParser.createSFInstance;
import static com.mgz.util.Constants.AFPBeginByte_0xA5;
import static com.mgz.util.UtilBinaryDecoding.parseInt;

/**
 * AFP parser ignoring all content but text nodes.
 */
public class AFPTextParser implements Iterator<String>, Iterable<String> {

  //---- Static

  /**
   * CLI tool for text extraction from AFP files.
   */
  public static void main(String[] args) throws FileNotFoundException {
    final AFPParserConfiguration configuration = new AFPParserConfiguration();
    configuration.setInputStream(new FileInputStream(args[0]));

    final AFPTextParser parser = new AFPTextParser(configuration);

    for (final String string : parser) {
      System.out.println(string);
    }
  }


  //---- Fields

  private final AFPParserConfiguration parserConf;
  private long nrOfBytesRead = 0;
  private boolean reachedEnd;
  private Queue<String> parsedStrings = new ArrayDeque<>();
  private InputStream input;


  //---- Constructor

  /**
   * Constructor.
   *
   * @param parserConfiguration see {@link AFPParserConfiguration}
   */
  public AFPTextParser(AFPParserConfiguration parserConfiguration) {
    parserConf = parserConfiguration;
    try {
      input = parserConfiguration.getInputStream();
    } catch (IOException e) {
      throw new IllegalArgumentException(e);
    }
  }


  //---- Methods

  /** {@inheritDoc} */
  @Override
  public Iterator<String> iterator() {
      return this;
  }

  /** {@inheritDoc} */
  @Override
  public boolean hasNext() {
    if (!parsedStrings.isEmpty()) {
      return true;
    }
    else if (reachedEnd) {
      return false;
    }
    else {
      try {
        return parseMoreStrings() > 0;
      } catch (AFPParserException e) {
        throw new UncheckedAFPException(e);
      }
    }
  }

  /** {@inheritDoc} */
  @Override
  public String next() {
    if (!hasNext()) {
      throw new NoSuchElementException();
    }

    return parsedStrings.remove();
  }

  /**
   * Parse (or skip) fields until the string cache is no longer empty or EOF is reached.
   *
   * @return number of new lines in the string cache
   */
  private int parseMoreStrings() throws AFPParserException {
    int stringCount = 0;

    while (!reachedEnd && stringCount == 0) {
      try {
        stringCount = parseNextField();
      } catch (IOException e) {
        throw new AFPParserException("Failed to parse structured field.", e);
      }
    }

    return stringCount;
  }

  /**
   * Parse a single structured field and push any text it contains to the string cache.
   *
   * @return number of new lines in the string cache
   */
  private int parseNextField() throws AFPParserException, IOException {
    // Find beginning of next structured field
    int marker;
    do {
      try {
        marker = readByte();
      } catch (EOFException e) {
        return 0;
      }
      nrOfBytesRead++;
    }
    while (marker != AFPBeginByte_0xA5);

    // Parse header of next structured field
    final int sfLength = parseInt(input, 2);
    final SFTypeID sfTypeID = SFTypeID.parse(input);

    // If it isn't text, skip it
    if (sfTypeID != SFTypeID.PTX_PresentationTextData) {
      skip(sfLength - 4);
      nrOfBytesRead += sfLength;
      return 0;
    }

    // Otherwise construct the field metadata object
    final StructuredFieldIntroducer metadata = new StructuredFieldIntroducer();
    metadata.setSFLength(sfLength);
    metadata.setSFTypeID(sfTypeID);
    metadata.setFileOffset(nrOfBytesRead);
    metadata.setFlagByte(SFFlag.valueOf(readByte()));
    metadata.setReserved(parseInt(input, 2));

    if (metadata.isFlagSet(hasExtension)) {
      final int sfExtensionLength = readByte() - 1;
      final byte[] extensionData = readBytes(sfExtensionLength);
      metadata.setExtensionData(extensionData);
    }

    // Then read the field data
    final int payloadLength = metadata.getSFLength() - metadata.getLengthOfStructuredFieldIntroducerIncludingExtension();
    final byte[] payload = readBytes(payloadLength);

    // Determine padding length
    final int payloadPaddingLength;
    if (metadata.isFlagSet(SFFlag.isPadded)) {
      if (payload[payloadLength - 1] == 0) {
        payloadPaddingLength = parseInt(payload, payloadLength - 3, 2);
      }
      else {
        payloadPaddingLength = payload[payloadLength - 1];
      }
    } else {
      payloadPaddingLength = 0;
    }

    // Parse the field data
    final StructuredField field = createSFInstance(metadata);
    field.decodeAFP(payload, 0, payloadLength - payloadPaddingLength, parserConf);

    // Extract the text
    if (!(field instanceof PTX_PresentationTextData)) {
      throw new IllegalStateException("Unskipped filed is no PTX_PresentationTextData node: " + field);
    }

    int stringCount = 0;
    for (final PTOCAControlSequence controlSequence : ((PTX_PresentationTextData) field).getControlSequences()) {
      if (controlSequence instanceof PTOCAControlSequence.TRN_TransparentData) {
        parsedStrings.add(((PTOCAControlSequence.TRN_TransparentData) controlSequence).getTransparentData());
        stringCount++;
      }
    }

    return stringCount;
  }

  private int readByte() throws IOException {
    final int value = input.read();

    if (value == -1) {
      throw eof();
    }

    return value;
  }

  private byte[] readBytes(int n) throws IOException {
    final byte[] data = new byte[n];
    int bytesReadTotal = 0;
    while (bytesReadTotal < n) {
      int bytesRead = input.read(data, bytesReadTotal, n - bytesReadTotal);

      if (bytesRead == 0) {
        throw eof();
      }

      bytesReadTotal += bytesRead;
    }

    return data;
  }

  private void skip(long n) throws IOException {
    long skippedTotal = 0;
    long skipped;
    while ((skipped = input.skip(n - skippedTotal)) < n - skippedTotal) {
      if (skipped == 0) {
        throw eof();
      }

      skippedTotal += skipped;
    }
  }

  private EOFException eof() {
    reachedEnd = true;
    return new EOFException("EOF at byte " + nrOfBytesRead);
  }

}
