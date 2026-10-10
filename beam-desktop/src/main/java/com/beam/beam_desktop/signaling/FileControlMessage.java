package com.beam.beam_desktop.signaling;

public class FileControlMessage {
    private String type;      // FILE_META, RESUME_OFFSET, FILE_COMPLETE, FILE_RESULT
    private String fileId;
    private String name;
    private Long size;
    private String mimeType;
    private String checksum;
    private Long offset;
    private Boolean ok;
    private String reason;

    public FileControlMessage() {}

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Long getSize() { return size; }
    public void setSize(Long size) { this.size = size; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }

    public Long getOffset() { return offset; }
    public void setOffset(Long offset) { this.offset = offset; }

    public Boolean getOk() { return ok; }
    public void setOk(Boolean ok) { this.ok = ok; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}