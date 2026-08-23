package com.homesoft.usb.desc.video;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class FormatDesc {
    public final byte formatIndex;
    public final byte numFrameDescriptors;
    public final String fourCc;
    public final byte defaultFrameIndex;
    public final List<FrameDesc> frameList = new ArrayList<>();

    public FormatDesc(byte formatIndex, byte numFrameDescriptors, String fourCc, byte defaultFrameIndex) {
        this.formatIndex = formatIndex;
        this.numFrameDescriptors = numFrameDescriptors;
        this.fourCc = fourCc;
        this.defaultFrameIndex = defaultFrameIndex;
    }

    public byte getFormatIndex() { return formatIndex; }
    public String getFourCc() { return fourCc; }
    public byte getDefaultFrameIndex() { return defaultFrameIndex; }
    public void addFrameDesc(FrameDesc frameDesc) { frameList.add(frameDesc); }

    public List<FrameDesc> getFrameList() {
        List<FrameDesc> sorted = new ArrayList<>(frameList);
        Collections.sort(sorted, new Comparator<FrameDesc>() {
            @Override
            public int compare(FrameDesc o1, FrameDesc o2) {
                return Integer.compare(o2.width * o2.height, o1.width * o1.height);
            }
        });
        return sorted;
    }

    public FrameDesc getFrameDesc(byte frameIndex) {
        for (FrameDesc desc : frameList) {
            if (desc.frameIndex == frameIndex) return desc;
        }
        return null;
    }

    public FrameDesc getClosestFrameDesc(int targetWidth, int targetHeight) {
        if (frameList.isEmpty()) return null;
        FrameDesc best = frameList.get(0);
        int bestDiff = Integer.MAX_VALUE;
        for (FrameDesc desc : frameList) {
            int diff = Math.abs(desc.width - targetWidth) + Math.abs(desc.height - targetHeight);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = desc;
            }
        }
        return best;
    }
}