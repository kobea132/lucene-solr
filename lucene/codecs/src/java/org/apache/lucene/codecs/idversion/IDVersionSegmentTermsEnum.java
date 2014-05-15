package org.apache.lucene.codecs.idversion;

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.io.PrintStream;

import org.apache.lucene.codecs.BlockTermState;
import org.apache.lucene.index.DocsAndPositionsEnum;
import org.apache.lucene.index.DocsEnum;
import org.apache.lucene.index.FieldInfo.IndexOptions;
import org.apache.lucene.index.TermState;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.ByteArrayDataInput;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.RamUsageEstimator;
import org.apache.lucene.util.fst.ByteSequenceOutputs;
import org.apache.lucene.util.fst.FST;
import org.apache.lucene.util.fst.Outputs;
import org.apache.lucene.util.fst.PairOutputs.Pair;
import org.apache.lucene.util.fst.PairOutputs;
import org.apache.lucene.util.fst.Util;

/** Iterates through terms in this field */
public final class IDVersionSegmentTermsEnum extends TermsEnum {

  final static Outputs<Pair<BytesRef,Long>> fstOutputs = VersionBlockTreeTermsWriter.getFSTOutputs();
  final static Pair<BytesRef,Long> NO_OUTPUT = fstOutputs.getNoOutput();

  // Lazy init:
  IndexInput in;

  private IDVersionSegmentTermsEnumFrame[] stack;
  private final IDVersionSegmentTermsEnumFrame staticFrame;
  IDVersionSegmentTermsEnumFrame currentFrame;
  boolean termExists;
  final VersionFieldReader fr;

  // nocommit make this public "for casting" and add a getVersion method?

  private int targetBeforeCurrentLength;

  private final ByteArrayDataInput scratchReader = new ByteArrayDataInput();

  // What prefix of the current term was present in the index:
  private int validIndexPrefix;

  // assert only:
  private boolean eof;

  final BytesRef term = new BytesRef();
  private final FST.BytesReader fstReader;

  @SuppressWarnings({"rawtypes","unchecked"}) private FST.Arc<Pair<BytesRef,Long>>[] arcs =
  new FST.Arc[1];

  public IDVersionSegmentTermsEnum(VersionFieldReader fr) throws IOException {
    this.fr = fr;

    //if (DEBUG) System.out.println("BTTR.init seg=" + segment);
    stack = new IDVersionSegmentTermsEnumFrame[0];
        
    // Used to hold seek by TermState, or cached seek
    staticFrame = new IDVersionSegmentTermsEnumFrame(this, -1);

    if (fr.index == null) {
      fstReader = null;
    } else {
      fstReader = fr.index.getBytesReader();
    }

    // Init w/ root block; don't use index since it may
    // not (and need not) have been loaded
    for(int arcIdx=0;arcIdx<arcs.length;arcIdx++) {
      arcs[arcIdx] = new FST.Arc<>();
    }

    currentFrame = staticFrame;
    final FST.Arc<Pair<BytesRef,Long>> arc;
    if (fr.index != null) {
      arc = fr.index.getFirstArc(arcs[0]);
      // Empty string prefix must have an output in the index!
      assert arc.isFinal();
    } else {
      arc = null;
    }
    currentFrame = staticFrame;
    //currentFrame = pushFrame(arc, rootCode, 0);
    //currentFrame.loadBlock();
    validIndexPrefix = 0;
    // if (DEBUG) {
    //   System.out.println("init frame state " + currentFrame.ord);
    //   printSeekState();
    // }

    //System.out.println();
    // computeBlockStats().print(System.out);
  }
      
  // Not private to avoid synthetic access$NNN methods
  void initIndexInput() {
    if (this.in == null) {
      this.in = fr.parent.in.clone();
    }
  }

  private IDVersionSegmentTermsEnumFrame getFrame(int ord) throws IOException {
    if (ord >= stack.length) {
      final IDVersionSegmentTermsEnumFrame[] next = new IDVersionSegmentTermsEnumFrame[ArrayUtil.oversize(1+ord, RamUsageEstimator.NUM_BYTES_OBJECT_REF)];
      System.arraycopy(stack, 0, next, 0, stack.length);
      for(int stackOrd=stack.length;stackOrd<next.length;stackOrd++) {
        next[stackOrd] = new IDVersionSegmentTermsEnumFrame(this, stackOrd);
      }
      stack = next;
    }
    assert stack[ord].ord == ord;
    return stack[ord];
  }

  private FST.Arc<Pair<BytesRef,Long>> getArc(int ord) {
    if (ord >= arcs.length) {
      @SuppressWarnings({"rawtypes","unchecked"}) final FST.Arc<Pair<BytesRef,Long>>[] next =
      new FST.Arc[ArrayUtil.oversize(1+ord, RamUsageEstimator.NUM_BYTES_OBJECT_REF)];
      System.arraycopy(arcs, 0, next, 0, arcs.length);
      for(int arcOrd=arcs.length;arcOrd<next.length;arcOrd++) {
        next[arcOrd] = new FST.Arc<>();
      }
      arcs = next;
    }
    return arcs[ord];
  }

  // Pushes a frame we seek'd to
  IDVersionSegmentTermsEnumFrame pushFrame(FST.Arc<Pair<BytesRef,Long>> arc, Pair<BytesRef,Long> frameData, int length) throws IOException {
    scratchReader.reset(frameData.output1.bytes, frameData.output1.offset, frameData.output1.length);
    final long code = scratchReader.readVLong();
    final long fpSeek = code >>> VersionBlockTreeTermsWriter.OUTPUT_FLAGS_NUM_BITS;
    final IDVersionSegmentTermsEnumFrame f = getFrame(1+currentFrame.ord);
    f.hasTerms = (code & VersionBlockTreeTermsWriter.OUTPUT_FLAG_HAS_TERMS) != 0;
    f.hasTermsOrig = f.hasTerms;
    f.isFloor = (code & VersionBlockTreeTermsWriter.OUTPUT_FLAG_IS_FLOOR) != 0;
    if (f.isFloor) {
      f.setFloorData(scratchReader, frameData.output1);
    }
    pushFrame(arc, fpSeek, length);

    return f;
  }

  // Pushes next'd frame or seek'd frame; we later
  // lazy-load the frame only when needed
  IDVersionSegmentTermsEnumFrame pushFrame(FST.Arc<Pair<BytesRef,Long>> arc, long fp, int length) throws IOException {
    final IDVersionSegmentTermsEnumFrame f = getFrame(1+currentFrame.ord);
    f.arc = arc;
    if (f.fpOrig == fp && f.nextEnt != -1) {
      //if (DEBUG) System.out.println("      push reused frame ord=" + f.ord + " fp=" + f.fp + " isFloor?=" + f.isFloor + " hasTerms=" + f.hasTerms + " pref=" + term + " nextEnt=" + f.nextEnt + " targetBeforeCurrentLength=" + targetBeforeCurrentLength + " term.length=" + term.length + " vs prefix=" + f.prefix);
      if (f.prefix > targetBeforeCurrentLength) {
        f.rewind();
      } else {
        // if (DEBUG) {
        //   System.out.println("        skip rewind!");
        // }
      }
      assert length == f.prefix;
    } else {
      f.nextEnt = -1;
      f.prefix = length;
      f.state.termBlockOrd = 0;
      f.fpOrig = f.fp = fp;
      f.lastSubFP = -1;
      // if (DEBUG) {
      //   final int sav = term.length;
      //   term.length = length;
      //   System.out.println("      push new frame ord=" + f.ord + " fp=" + f.fp + " hasTerms=" + f.hasTerms + " isFloor=" + f.isFloor + " pref=" + brToString(term));
      //   term.length = sav;
      // }
    }

    return f;
  }

  // asserts only
  private boolean clearEOF() {
    eof = false;
    return true;
  }

  // asserts only
  private boolean setEOF() {
    eof = true;
    return true;
  }

  // nocommit we need a seekExact(BytesRef target, long minVersion) API?

  @Override
  public boolean seekExact(final BytesRef target) throws IOException {
    return seekExact(target, 0);
  }

  /** Returns false if the term deos not exist, or it exists but its version is < minIDVersion. */
  public boolean seekExact(final BytesRef target, long minIDVersion) throws IOException {

    if (fr.index == null) {
      throw new IllegalStateException("terms index was not loaded");
    }

    if (term.bytes.length <= target.length) {
      term.bytes = ArrayUtil.grow(term.bytes, 1+target.length);
    }

    assert clearEOF();

    // if (DEBUG) {
    //   System.out.println("\nBTTR.seekExact seg=" + segment + " target=" + fieldInfo.name + ":" + brToString(target) + " current=" + brToString(term) + " (exists?=" + termExists + ") validIndexPrefix=" + validIndexPrefix);
    //   printSeekState();
    // }

    FST.Arc<Pair<BytesRef,Long>> arc;
    int targetUpto;
    Pair<BytesRef,Long> output;

    targetBeforeCurrentLength = currentFrame.ord;

    if (currentFrame != staticFrame) {

      // We are already seek'd; find the common
      // prefix of new seek term vs current term and
      // re-use the corresponding seek state.  For
      // example, if app first seeks to foobar, then
      // seeks to foobaz, we can re-use the seek state
      // for the first 5 bytes.

      // if (DEBUG) {
      //   System.out.println("  re-use current seek state validIndexPrefix=" + validIndexPrefix);
      // }

      arc = arcs[0];
      assert arc.isFinal();
      output = arc.output;
      targetUpto = 0;

      IDVersionSegmentTermsEnumFrame lastFrame = stack[0];
      assert validIndexPrefix <= term.length;

      final int targetLimit = Math.min(target.length, validIndexPrefix);

      int cmp = 0;

      // TODO: reverse vLong byte order for better FST
      // prefix output sharing

      // First compare up to valid seek frames:
      while (targetUpto < targetLimit) {
        cmp = (term.bytes[targetUpto]&0xFF) - (target.bytes[target.offset + targetUpto]&0xFF);
        // if (DEBUG) {
        //   System.out.println("    cycle targetUpto=" + targetUpto + " (vs limit=" + targetLimit + ") cmp=" + cmp + " (targetLabel=" + (char) (target.bytes[target.offset + targetUpto]) + " vs termLabel=" + (char) (term.bytes[targetUpto]) + ")"   + " arc.output=" + arc.output + " output=" + output);
        // }
        if (cmp != 0) {
          break;
        }
        arc = arcs[1+targetUpto];
        //if (arc.label != (target.bytes[target.offset + targetUpto] & 0xFF)) {
        //System.out.println("FAIL: arc.label=" + (char) arc.label + " targetLabel=" + (char) (target.bytes[target.offset + targetUpto] & 0xFF));
        //}
        assert arc.label == (target.bytes[target.offset + targetUpto] & 0xFF): "arc.label=" + (char) arc.label + " targetLabel=" + (char) (target.bytes[target.offset + targetUpto] & 0xFF);
        if (arc.output != NO_OUTPUT) {
          output = fstOutputs.add(output, arc.output);
        }
        if (arc.isFinal()) {
          lastFrame = stack[1+lastFrame.ord];
        }
        targetUpto++;
      }

      if (cmp == 0) {
        final int targetUptoMid = targetUpto;

        // Second compare the rest of the term, but
        // don't save arc/output/frame; we only do this
        // to find out if the target term is before,
        // equal or after the current term
        final int targetLimit2 = Math.min(target.length, term.length);
        while (targetUpto < targetLimit2) {
          cmp = (term.bytes[targetUpto]&0xFF) - (target.bytes[target.offset + targetUpto]&0xFF);
          // if (DEBUG) {
          //   System.out.println("    cycle2 targetUpto=" + targetUpto + " (vs limit=" + targetLimit + ") cmp=" + cmp + " (targetLabel=" + (char) (target.bytes[target.offset + targetUpto]) + " vs termLabel=" + (char) (term.bytes[targetUpto]) + ")");
          // }
          if (cmp != 0) {
            break;
          }
          targetUpto++;
        }

        if (cmp == 0) {
          cmp = term.length - target.length;
        }
        targetUpto = targetUptoMid;
      }

      if (cmp < 0) {
        // Common case: target term is after current
        // term, ie, app is seeking multiple terms
        // in sorted order
        // if (DEBUG) {
        //   System.out.println("  target is after current (shares prefixLen=" + targetUpto + "); frame.ord=" + lastFrame.ord);
        // }
        currentFrame = lastFrame;

      } else if (cmp > 0) {
        // Uncommon case: target term
        // is before current term; this means we can
        // keep the currentFrame but we must rewind it
        // (so we scan from the start)
        targetBeforeCurrentLength = 0;
        // if (DEBUG) {
        //   System.out.println("  target is before current (shares prefixLen=" + targetUpto + "); rewind frame ord=" + lastFrame.ord);
        // }
        currentFrame = lastFrame;
        currentFrame.rewind();
      } else {
        // Target is exactly the same as current term
        assert term.length == target.length;
        if (termExists) {
          // if (DEBUG) {
          //   System.out.println("  target is same as current; return true");
          // }
          return true;
        } else {
          // if (DEBUG) {
          //   System.out.println("  target is same as current but term doesn't exist");
          // }
        }
        //validIndexPrefix = currentFrame.depth;
        //term.length = target.length;
        //return termExists;
      }

    } else {

      targetBeforeCurrentLength = -1;
      arc = fr.index.getFirstArc(arcs[0]);

      // Empty string prefix must have an output (block) in the index!
      assert arc.isFinal();
      assert arc.output != null;

      // if (DEBUG) {
      //   System.out.println("    no seek state; push root frame");
      // }

      output = arc.output;

      currentFrame = staticFrame;

      //term.length = 0;
      targetUpto = 0;
      currentFrame = pushFrame(arc, fstOutputs.add(output, arc.nextFinalOutput), 0);
    }

    // if (DEBUG) {
    //   System.out.println("  start index loop targetUpto=" + targetUpto + " output=" + output + " currentFrame.ord=" + currentFrame.ord + " targetBeforeCurrentLength=" + targetBeforeCurrentLength);
    // }

    while (targetUpto < target.length) {

      final int targetLabel = target.bytes[target.offset + targetUpto] & 0xFF;

      final FST.Arc<Pair<BytesRef,Long>> nextArc = fr.index.findTargetArc(targetLabel, arc, getArc(1+targetUpto), fstReader);

      if (nextArc == null) {

        // Index is exhausted
        // if (DEBUG) {
        //   System.out.println("    index: index exhausted label=" + ((char) targetLabel) + " " + toHex(targetLabel));
        // }
            
        validIndexPrefix = currentFrame.prefix;
        //validIndexPrefix = targetUpto;

        currentFrame.scanToFloorFrame(target);

        if (!currentFrame.hasTerms) {
          termExists = false;
          term.bytes[targetUpto] = (byte) targetLabel;
          term.length = 1+targetUpto;
          // if (DEBUG) {
          //   System.out.println("  FAST NOT_FOUND term=" + brToString(term));
          // }
          return false;
        }

        if ((Long.MAX_VALUE-output.output2) < minIDVersion) {
          // The max version for all terms in this block is lower than the minVersion
          return false;
        }

        currentFrame.loadBlock();

        final SeekStatus result = currentFrame.scanToTerm(target, true);            
        if (result == SeekStatus.FOUND) {
          // if (DEBUG) {
          //   System.out.println("  return FOUND term=" + term.utf8ToString() + " " + term);
          // }

          currentFrame.decodeMetaData();
          if (((IDVersionTermState) currentFrame.state).idVersion < minIDVersion) {
            // The max version for this term is lower than the minVersion
            return false;
          }
          return true;
        } else {
          // if (DEBUG) {
          //   System.out.println("  got " + result + "; return NOT_FOUND term=" + brToString(term));
          // }
          return false;
        }
      } else {
        // Follow this arc
        arc = nextArc;
        term.bytes[targetUpto] = (byte) targetLabel;
        // Aggregate output as we go:
        assert arc.output != null;
        if (arc.output != NO_OUTPUT) {
          output = fstOutputs.add(output, arc.output);
        }

        // if (DEBUG) {
        //   System.out.println("    index: follow label=" + toHex(target.bytes[target.offset + targetUpto]&0xff) + " arc.output=" + arc.output + " arc.nfo=" + arc.nextFinalOutput);
        // }
        targetUpto++;

        if (arc.isFinal()) {
          //if (DEBUG) System.out.println("    arc is final!");
          currentFrame = pushFrame(arc, fstOutputs.add(output, arc.nextFinalOutput), targetUpto);
          //if (DEBUG) System.out.println("    curFrame.ord=" + currentFrame.ord + " hasTerms=" + currentFrame.hasTerms);
        }
      }
    }

    //validIndexPrefix = targetUpto;
    validIndexPrefix = currentFrame.prefix;

    currentFrame.scanToFloorFrame(target);

    // Target term is entirely contained in the index:
    if (!currentFrame.hasTerms) {
      termExists = false;
      term.length = targetUpto;
      // if (DEBUG) {
      //   System.out.println("  FAST NOT_FOUND term=" + brToString(term));
      // }
      return false;
    }

    currentFrame.loadBlock();

    final SeekStatus result = currentFrame.scanToTerm(target, true);            
    if (result == SeekStatus.FOUND) {
      // if (DEBUG) {
      //   System.out.println("  return FOUND term=" + term.utf8ToString() + " " + term);
      // }
      return true;
    } else {
      // if (DEBUG) {
      //   System.out.println("  got result " + result + "; return NOT_FOUND term=" + term.utf8ToString());
      // }

      return false;
    }
  }

  @Override
  public SeekStatus seekCeil(final BytesRef target) throws IOException {
    if (fr.index == null) {
      throw new IllegalStateException("terms index was not loaded");
    }
   
    if (term.bytes.length <= target.length) {
      term.bytes = ArrayUtil.grow(term.bytes, 1+target.length);
    }

    assert clearEOF();

    //if (DEBUG) {
    //System.out.println("\nBTTR.seekCeil seg=" + segment + " target=" + fieldInfo.name + ":" + target.utf8ToString() + " " + target + " current=" + brToString(term) + " (exists?=" + termExists + ") validIndexPrefix=  " + validIndexPrefix);
    //printSeekState();
    //}

    FST.Arc<Pair<BytesRef,Long>> arc;
    int targetUpto;
    Pair<BytesRef,Long> output;

    targetBeforeCurrentLength = currentFrame.ord;

    if (currentFrame != staticFrame) {

      // We are already seek'd; find the common
      // prefix of new seek term vs current term and
      // re-use the corresponding seek state.  For
      // example, if app first seeks to foobar, then
      // seeks to foobaz, we can re-use the seek state
      // for the first 5 bytes.

      //if (DEBUG) {
      //System.out.println("  re-use current seek state validIndexPrefix=" + validIndexPrefix);
      //}

      arc = arcs[0];
      assert arc.isFinal();
      output = arc.output;
      targetUpto = 0;
          
      IDVersionSegmentTermsEnumFrame lastFrame = stack[0];
      assert validIndexPrefix <= term.length;

      final int targetLimit = Math.min(target.length, validIndexPrefix);

      int cmp = 0;

      // TOOD: we should write our vLong backwards (MSB
      // first) to get better sharing from the FST

      // First compare up to valid seek frames:
      while (targetUpto < targetLimit) {
        cmp = (term.bytes[targetUpto]&0xFF) - (target.bytes[target.offset + targetUpto]&0xFF);
        //if (DEBUG) {
        //System.out.println("    cycle targetUpto=" + targetUpto + " (vs limit=" + targetLimit + ") cmp=" + cmp + " (targetLabel=" + (char) (target.bytes[target.offset + targetUpto]) + " vs termLabel=" + (char) (term.bytes[targetUpto]) + ")"   + " arc.output=" + arc.output + " output=" + output);
        //}
        if (cmp != 0) {
          break;
        }
        arc = arcs[1+targetUpto];
        assert arc.label == (target.bytes[target.offset + targetUpto] & 0xFF): "arc.label=" + (char) arc.label + " targetLabel=" + (char) (target.bytes[target.offset + targetUpto] & 0xFF);
        // TOOD: we could save the outputs in local
        // byte[][] instead of making new objs ever
        // seek; but, often the FST doesn't have any
        // shared bytes (but this could change if we
        // reverse vLong byte order)
        if (arc.output != NO_OUTPUT) {
          output = fstOutputs.add(output, arc.output);
        }
        if (arc.isFinal()) {
          lastFrame = stack[1+lastFrame.ord];
        }
        targetUpto++;
      }


      if (cmp == 0) {
        final int targetUptoMid = targetUpto;
        // Second compare the rest of the term, but
        // don't save arc/output/frame:
        final int targetLimit2 = Math.min(target.length, term.length);
        while (targetUpto < targetLimit2) {
          cmp = (term.bytes[targetUpto]&0xFF) - (target.bytes[target.offset + targetUpto]&0xFF);
          //if (DEBUG) {
          //System.out.println("    cycle2 targetUpto=" + targetUpto + " (vs limit=" + targetLimit + ") cmp=" + cmp + " (targetLabel=" + (char) (target.bytes[target.offset + targetUpto]) + " vs termLabel=" + (char) (term.bytes[targetUpto]) + ")");
          //}
          if (cmp != 0) {
            break;
          }
          targetUpto++;
        }

        if (cmp == 0) {
          cmp = term.length - target.length;
        }
        targetUpto = targetUptoMid;
      }

      if (cmp < 0) {
        // Common case: target term is after current
        // term, ie, app is seeking multiple terms
        // in sorted order
        //if (DEBUG) {
        //System.out.println("  target is after current (shares prefixLen=" + targetUpto + "); clear frame.scanned ord=" + lastFrame.ord);
        //}
        currentFrame = lastFrame;

      } else if (cmp > 0) {
        // Uncommon case: target term
        // is before current term; this means we can
        // keep the currentFrame but we must rewind it
        // (so we scan from the start)
        targetBeforeCurrentLength = 0;
        //if (DEBUG) {
        //System.out.println("  target is before current (shares prefixLen=" + targetUpto + "); rewind frame ord=" + lastFrame.ord);
        //}
        currentFrame = lastFrame;
        currentFrame.rewind();
      } else {
        // Target is exactly the same as current term
        assert term.length == target.length;
        if (termExists) {
          //if (DEBUG) {
          //System.out.println("  target is same as current; return FOUND");
          //}
          return SeekStatus.FOUND;
        } else {
          //if (DEBUG) {
          //System.out.println("  target is same as current but term doesn't exist");
          //}
        }
      }

    } else {

      targetBeforeCurrentLength = -1;
      arc = fr.index.getFirstArc(arcs[0]);

      // Empty string prefix must have an output (block) in the index!
      assert arc.isFinal();
      assert arc.output != null;

      //if (DEBUG) {
      //System.out.println("    no seek state; push root frame");
      //}

      output = arc.output;

      currentFrame = staticFrame;

      //term.length = 0;
      targetUpto = 0;
      currentFrame = pushFrame(arc, fstOutputs.add(output, arc.nextFinalOutput), 0);
    }

    //if (DEBUG) {
    //System.out.println("  start index loop targetUpto=" + targetUpto + " output=" + output + " currentFrame.ord+1=" + currentFrame.ord + " targetBeforeCurrentLength=" + targetBeforeCurrentLength);
    //}

    while (targetUpto < target.length) {

      final int targetLabel = target.bytes[target.offset + targetUpto] & 0xFF;

      final FST.Arc<Pair<BytesRef,Long>> nextArc = fr.index.findTargetArc(targetLabel, arc, getArc(1+targetUpto), fstReader);

      if (nextArc == null) {

        // Index is exhausted
        // if (DEBUG) {
        //   System.out.println("    index: index exhausted label=" + ((char) targetLabel) + " " + toHex(targetLabel));
        // }
            
        validIndexPrefix = currentFrame.prefix;
        //validIndexPrefix = targetUpto;

        currentFrame.scanToFloorFrame(target);

        currentFrame.loadBlock();

        final SeekStatus result = currentFrame.scanToTerm(target, false);
        if (result == SeekStatus.END) {
          term.copyBytes(target);
          termExists = false;

          if (next() != null) {
            //if (DEBUG) {
            //System.out.println("  return NOT_FOUND term=" + brToString(term) + " " + term);
            //}
            return SeekStatus.NOT_FOUND;
          } else {
            //if (DEBUG) {
            //System.out.println("  return END");
            //}
            return SeekStatus.END;
          }
        } else {
          //if (DEBUG) {
          //System.out.println("  return " + result + " term=" + brToString(term) + " " + term);
          //}
          return result;
        }
      } else {
        // Follow this arc
        term.bytes[targetUpto] = (byte) targetLabel;
        arc = nextArc;
        // Aggregate output as we go:
        assert arc.output != null;
        if (arc.output != NO_OUTPUT) {
          output = fstOutputs.add(output, arc.output);
        }

        //if (DEBUG) {
        //System.out.println("    index: follow label=" + toHex(target.bytes[target.offset + targetUpto]&0xff) + " arc.output=" + arc.output + " arc.nfo=" + arc.nextFinalOutput);
        //}
        targetUpto++;

        if (arc.isFinal()) {
          //if (DEBUG) System.out.println("    arc is final!");
          currentFrame = pushFrame(arc, fstOutputs.add(output, arc.nextFinalOutput), targetUpto);
          //if (DEBUG) System.out.println("    curFrame.ord=" + currentFrame.ord + " hasTerms=" + currentFrame.hasTerms);
        }
      }
    }

    //validIndexPrefix = targetUpto;
    validIndexPrefix = currentFrame.prefix;

    currentFrame.scanToFloorFrame(target);

    currentFrame.loadBlock();

    final SeekStatus result = currentFrame.scanToTerm(target, false);

    if (result == SeekStatus.END) {
      term.copyBytes(target);
      termExists = false;
      if (next() != null) {
        //if (DEBUG) {
        //System.out.println("  return NOT_FOUND term=" + term.utf8ToString() + " " + term);
        //}
        return SeekStatus.NOT_FOUND;
      } else {
        //if (DEBUG) {
        //System.out.println("  return END");
        //}
        return SeekStatus.END;
      }
    } else {
      return result;
    }
  }

  @SuppressWarnings("unused")
  private void printSeekState(PrintStream out) throws IOException {
    if (currentFrame == staticFrame) {
      out.println("  no prior seek");
    } else {
      out.println("  prior seek state:");
      int ord = 0;
      boolean isSeekFrame = true;
      while(true) {
        IDVersionSegmentTermsEnumFrame f = getFrame(ord);
        assert f != null;
        final BytesRef prefix = new BytesRef(term.bytes, 0, f.prefix);
        if (f.nextEnt == -1) {
          out.println("    frame " + (isSeekFrame ? "(seek)" : "(next)") + " ord=" + ord + " fp=" + f.fp + (f.isFloor ? (" (fpOrig=" + f.fpOrig + ")") : "") + " prefixLen=" + f.prefix + " prefix=" + prefix + (f.nextEnt == -1 ? "" : (" (of " + f.entCount + ")")) + " hasTerms=" + f.hasTerms + " isFloor=" + f.isFloor + " code=" + ((f.fp<<VersionBlockTreeTermsWriter.OUTPUT_FLAGS_NUM_BITS) + (f.hasTerms ? VersionBlockTreeTermsWriter.OUTPUT_FLAG_HAS_TERMS:0) + (f.isFloor ? VersionBlockTreeTermsWriter.OUTPUT_FLAG_IS_FLOOR:0)) + " isLastInFloor=" + f.isLastInFloor + " mdUpto=" + f.metaDataUpto + " tbOrd=" + f.getTermBlockOrd());
        } else {
          out.println("    frame " + (isSeekFrame ? "(seek, loaded)" : "(next, loaded)") + " ord=" + ord + " fp=" + f.fp + (f.isFloor ? (" (fpOrig=" + f.fpOrig + ")") : "") + " prefixLen=" + f.prefix + " prefix=" + prefix + " nextEnt=" + f.nextEnt + (f.nextEnt == -1 ? "" : (" (of " + f.entCount + ")")) + " hasTerms=" + f.hasTerms + " isFloor=" + f.isFloor + " code=" + ((f.fp<<VersionBlockTreeTermsWriter.OUTPUT_FLAGS_NUM_BITS) + (f.hasTerms ? VersionBlockTreeTermsWriter.OUTPUT_FLAG_HAS_TERMS:0) + (f.isFloor ? VersionBlockTreeTermsWriter.OUTPUT_FLAG_IS_FLOOR:0)) + " lastSubFP=" + f.lastSubFP + " isLastInFloor=" + f.isLastInFloor + " mdUpto=" + f.metaDataUpto + " tbOrd=" + f.getTermBlockOrd());
        }
        if (fr.index != null) {
          assert !isSeekFrame || f.arc != null: "isSeekFrame=" + isSeekFrame + " f.arc=" + f.arc;
          if (f.prefix > 0 && isSeekFrame && f.arc.label != (term.bytes[f.prefix-1]&0xFF)) {
            out.println("      broken seek state: arc.label=" + (char) f.arc.label + " vs term byte=" + (char) (term.bytes[f.prefix-1]&0xFF));
            throw new RuntimeException("seek state is broken");
          }
          Pair<BytesRef,Long> output = Util.get(fr.index, prefix);
          if (output == null) {
            out.println("      broken seek state: prefix is not final in index");
            throw new RuntimeException("seek state is broken");
          } else if (isSeekFrame && !f.isFloor) {
            final ByteArrayDataInput reader = new ByteArrayDataInput(output.output1.bytes, output.output1.offset, output.output1.length);
            final long codeOrig = reader.readVLong();
            final long code = (f.fp << VersionBlockTreeTermsWriter.OUTPUT_FLAGS_NUM_BITS) | (f.hasTerms ? VersionBlockTreeTermsWriter.OUTPUT_FLAG_HAS_TERMS:0) | (f.isFloor ? VersionBlockTreeTermsWriter.OUTPUT_FLAG_IS_FLOOR:0);
            if (codeOrig != code) {
              out.println("      broken seek state: output code=" + codeOrig + " doesn't match frame code=" + code);
              throw new RuntimeException("seek state is broken");
            }
          }
        }
        if (f == currentFrame) {
          break;
        }
        if (f.prefix == validIndexPrefix) {
          isSeekFrame = false;
        }
        ord++;
      }
    }
  }

  /* Decodes only the term bytes of the next term.  If caller then asks for
     metadata, ie docFreq, totalTermFreq or pulls a D/&PEnum, we then (lazily)
     decode all metadata up to the current term. */
  @Override
  public BytesRef next() throws IOException {

    if (in == null) {
      // Fresh TermsEnum; seek to first term:
      final FST.Arc<Pair<BytesRef,Long>> arc;
      if (fr.index != null) {
        arc = fr.index.getFirstArc(arcs[0]);
        // Empty string prefix must have an output in the index!
        assert arc.isFinal();
      } else {
        arc = null;
      }
      currentFrame = pushFrame(arc, fr.rootCode, 0);
      currentFrame.loadBlock();
    }

    targetBeforeCurrentLength = currentFrame.ord;

    assert !eof;
    //if (DEBUG) {
    //System.out.println("\nBTTR.next seg=" + segment + " term=" + brToString(term) + " termExists?=" + termExists + " field=" + fieldInfo.name + " termBlockOrd=" + currentFrame.state.termBlockOrd + " validIndexPrefix=" + validIndexPrefix);
    //printSeekState();
    //}

    if (currentFrame == staticFrame) {
      // If seek was previously called and the term was
      // cached, or seek(TermState) was called, usually
      // caller is just going to pull a D/&PEnum or get
      // docFreq, etc.  But, if they then call next(),
      // this method catches up all internal state so next()
      // works properly:
      //if (DEBUG) System.out.println("  re-seek to pending term=" + term.utf8ToString() + " " + term);
      final boolean result = seekExact(term);
      assert result;
    }

    // Pop finished blocks
    while (currentFrame.nextEnt == currentFrame.entCount) {
      if (!currentFrame.isLastInFloor) {
        currentFrame.loadNextFloorBlock();
      } else {
        //if (DEBUG) System.out.println("  pop frame");
        if (currentFrame.ord == 0) {
          //if (DEBUG) System.out.println("  return null");
          assert setEOF();
          term.length = 0;
          validIndexPrefix = 0;
          currentFrame.rewind();
          termExists = false;
          return null;
        }
        final long lastFP = currentFrame.fpOrig;
        currentFrame = stack[currentFrame.ord-1];

        if (currentFrame.nextEnt == -1 || currentFrame.lastSubFP != lastFP) {
          // We popped into a frame that's not loaded
          // yet or not scan'd to the right entry
          currentFrame.scanToFloorFrame(term);
          currentFrame.loadBlock();
          currentFrame.scanToSubBlock(lastFP);
        }

        // Note that the seek state (last seek) has been
        // invalidated beyond this depth
        validIndexPrefix = Math.min(validIndexPrefix, currentFrame.prefix);
        //if (DEBUG) {
        //System.out.println("  reset validIndexPrefix=" + validIndexPrefix);
        //}
      }
    }

    while(true) {
      if (currentFrame.next()) {
        // Push to new block:
        //if (DEBUG) System.out.println("  push frame");
        currentFrame = pushFrame(null, currentFrame.lastSubFP, term.length);
        // This is a "next" frame -- even if it's
        // floor'd we must pretend it isn't so we don't
        // try to scan to the right floor frame:
        currentFrame.isFloor = false;
        //currentFrame.hasTerms = true;
        currentFrame.loadBlock();
      } else {
        //if (DEBUG) System.out.println("  return term=" + term.utf8ToString() + " " + term + " currentFrame.ord=" + currentFrame.ord);
        return term;
      }
    }
  }

  @Override
  public BytesRef term() {
    assert !eof;
    return term;
  }

  @Override
  public int docFreq() throws IOException {
    assert !eof;
    //if (DEBUG) System.out.println("BTR.docFreq");
    currentFrame.decodeMetaData();
    //if (DEBUG) System.out.println("  return " + currentFrame.state.docFreq);
    return currentFrame.state.docFreq;
  }

  @Override
  public long totalTermFreq() throws IOException {
    assert !eof;
    currentFrame.decodeMetaData();
    return currentFrame.state.totalTermFreq;
  }

  @Override
  public DocsEnum docs(Bits skipDocs, DocsEnum reuse, int flags) throws IOException {
    assert !eof;
    //if (DEBUG) {
    //System.out.println("BTTR.docs seg=" + segment);
    //}
    currentFrame.decodeMetaData();
    //if (DEBUG) {
    //System.out.println("  state=" + currentFrame.state);
    //}
    return fr.parent.postingsReader.docs(fr.fieldInfo, currentFrame.state, skipDocs, reuse, flags);
  }

  @Override
  public DocsAndPositionsEnum docsAndPositions(Bits skipDocs, DocsAndPositionsEnum reuse, int flags) throws IOException {
    if (fr.fieldInfo.getIndexOptions().compareTo(IndexOptions.DOCS_AND_FREQS_AND_POSITIONS) < 0) {
      // Positions were not indexed:
      return null;
    }

    assert !eof;
    currentFrame.decodeMetaData();
    return fr.parent.postingsReader.docsAndPositions(fr.fieldInfo, currentFrame.state, skipDocs, reuse, flags);
  }

  @Override
  public void seekExact(BytesRef target, TermState otherState) {
    // if (DEBUG) {
    //   System.out.println("BTTR.seekExact termState seg=" + segment + " target=" + target.utf8ToString() + " " + target + " state=" + otherState);
    // }
    assert clearEOF();
    if (target.compareTo(term) != 0 || !termExists) {
      assert otherState != null && otherState instanceof BlockTermState;
      currentFrame = staticFrame;
      currentFrame.state.copyFrom(otherState);
      term.copyBytes(target);
      currentFrame.metaDataUpto = currentFrame.getTermBlockOrd();
      assert currentFrame.metaDataUpto > 0;
      validIndexPrefix = 0;
    } else {
      // if (DEBUG) {
      //   System.out.println("  skip seek: already on target state=" + currentFrame.state);
      // }
    }
  }
      
  @Override
  public TermState termState() throws IOException {
    assert !eof;
    currentFrame.decodeMetaData();
    TermState ts = currentFrame.state.clone();
    //if (DEBUG) System.out.println("BTTR.termState seg=" + segment + " state=" + ts);
    return ts;
  }

  @Override
  public void seekExact(long ord) {
    throw new UnsupportedOperationException();
  }

  @Override
  public long ord() {
    throw new UnsupportedOperationException();
  }
}