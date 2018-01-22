package com.alibaba.sdk.android.oss.internal;

import android.text.TextUtils;

import com.alibaba.sdk.android.oss.ClientException;
import com.alibaba.sdk.android.oss.ServiceException;
import com.alibaba.sdk.android.oss.callback.OSSCompletedCallback;
import com.alibaba.sdk.android.oss.common.OSSLog;
import com.alibaba.sdk.android.oss.common.utils.BinaryUtil;
import com.alibaba.sdk.android.oss.common.utils.OSSSharedPreferences;
import com.alibaba.sdk.android.oss.common.utils.OSSUtils;
import com.alibaba.sdk.android.oss.model.AbortMultipartUploadRequest;
import com.alibaba.sdk.android.oss.model.CompleteMultipartUploadResult;
import com.alibaba.sdk.android.oss.model.InitiateMultipartUploadRequest;
import com.alibaba.sdk.android.oss.model.InitiateMultipartUploadResult;
import com.alibaba.sdk.android.oss.model.ListPartsRequest;
import com.alibaba.sdk.android.oss.model.ListPartsResult;
import com.alibaba.sdk.android.oss.model.PartETag;
import com.alibaba.sdk.android.oss.model.PartSummary;
import com.alibaba.sdk.android.oss.model.ResumableUploadRequest;
import com.alibaba.sdk.android.oss.model.ResumableUploadResult;
import com.alibaba.sdk.android.oss.network.ExecutionContext;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Created by jingdan on 2017/10/30.
 */

public class ResumableUploadTask extends BaseMultipartUploadTask<ResumableUploadRequest,
        ResumableUploadResult> implements Callable<ResumableUploadResult> {

    private File mRecordFile;
    private List<Integer> mAlreadyUploadIndex = new ArrayList<Integer>();
    private long mFirstPartSize;
    private OSSSharedPreferences mSp;
    private File mCRC64RecordFile;

    public ResumableUploadTask(ResumableUploadRequest request,
                               OSSCompletedCallback<ResumableUploadRequest, ResumableUploadResult> completedCallback,
                               ExecutionContext context, InternalRequestOperation apiOperation) {
        super(apiOperation, request, completedCallback, context);
        mSp = OSSSharedPreferences.instance(mContext.getApplicationContext());
    }

    @Override
    protected void initMultipartUploadId() throws IOException, ClientException, ServiceException {
        String uploadFilePath = mRequest.getUploadFilePath();
        mUploadedLength = 0;
        mUploadFile = new File(uploadFilePath);
        mFileLength = mUploadFile.length();
        if (mFileLength == 0) {
            throw new ClientException("file length must not be 0");
        }
        Map<Integer, Long> recordCrc64 = null;

        if (!OSSUtils.isEmptyString(mRequest.getRecordDirectory())) {
            String fileMd5 = BinaryUtil.calculateMd5Str(uploadFilePath);
            String recordFileName = BinaryUtil.calculateMd5Str((fileMd5 + mRequest.getBucketName()
                    + mRequest.getObjectKey() + String.valueOf(mRequest.getPartSize()) + (mCheckCRC64 ? "-crc64" : "")).getBytes());
            String recordPath = mRequest.getRecordDirectory() + File.separator + recordFileName;

            mRecordFile = new File(recordPath);
            if (mRecordFile.exists()) {
                BufferedReader br = new BufferedReader(new FileReader(mRecordFile));
                mUploadId = br.readLine();
                br.close();
                OSSLog.logDebug("[initUploadId] - Found record file, uploadid: " + mUploadId);
            }

            if (!OSSUtils.isEmptyString(mUploadId)) {
                if (mCheckCRC64) {
                    String filePath = mRequest.getRecordDirectory() + File.separator + mUploadId;
                    File crc64Record = new File(filePath);
                    if (crc64Record.exists()) {
                        FileInputStream fs = new FileInputStream(crc64Record);//创建文件字节输出流对象
                        ObjectInputStream ois = new ObjectInputStream(fs);

                        try {
                            recordCrc64 = (Map<Integer, Long>) ois.readObject();
                            crc64Record.delete();
                        } catch (ClassNotFoundException e) {
                            OSSLog.logThrowable2Local(e);
                        } finally {
                            if (ois != null)
                                ois.close();
                            crc64Record.delete();
                        }
                    }
                }

                ListPartsRequest listParts = new ListPartsRequest(mRequest.getBucketName(), mRequest.getObjectKey(), mUploadId);
                OSSAsyncTask<ListPartsResult> task = mApiOperation.listParts(listParts, null);
                try {
                    List<PartSummary> parts = task.getResult().getParts();
                    for (int i = 0; i < parts.size(); i++) {
                        PartSummary part = parts.get(i);
                        PartETag partETag = new PartETag(part.getPartNumber(), part.getETag());
                        partETag.setPartSize(part.getSize());

                        if (recordCrc64 != null && recordCrc64.size() > 0) {
                            if (recordCrc64.containsKey(partETag.getPartNumber())) {
                                partETag.setCRC64(recordCrc64.get(partETag.getPartNumber()));
                            }
                        }

                        mPartETags.add(partETag);
                        mUploadedLength += part.getSize();
                        mAlreadyUploadIndex.add(part.getPartNumber());
                        if (i == 0) {
                            mFirstPartSize = part.getSize();
                        }
                    }
                } catch (ServiceException e) {
                    if (e.getStatusCode() == 404) {
                        mUploadId = null;
                    } else {
                        throw e;
                    }
                } catch (ClientException e) {
                    throw e;
                }
                task.waitUntilFinished();
            }

            if (!mRecordFile.exists() && !mRecordFile.createNewFile()) {
                throw new ClientException("Can't create file at path: " + mRecordFile.getAbsolutePath()
                        + "\nPlease make sure the directory exist!");
            }
        }

        if (OSSUtils.isEmptyString(mUploadId)) {
            InitiateMultipartUploadRequest init = new InitiateMultipartUploadRequest(
                    mRequest.getBucketName(), mRequest.getObjectKey(), mRequest.getMetadata());

            InitiateMultipartUploadResult initResult = mApiOperation.initMultipartUpload(init, null).getResult();

            mUploadId = initResult.getUploadId();

            if (mRecordFile != null) {
                BufferedWriter bw = new BufferedWriter(new FileWriter(mRecordFile));
                bw.write(mUploadId);
                bw.close();
            }
        }

        mRequest.setUploadId(mUploadId);


    }

    /**
     * check part size
     *
     * @param partAttr
     */
    public void checkPartSize(int[] partAttr) {
        long partSize = mRequest.getPartSize();
        if (mFirstPartSize > 0){
            partSize = mFirstPartSize;
        }

        int partNumber = (int) (mFileLength / partSize);
        if (mFileLength % partSize != 0) {
            partNumber = partNumber + 1;
        }
        if (partNumber == 1){
            partSize = mFileLength;
        }else if (partNumber > 5000) {
            if (mFirstPartSize > 0){
                //do not change
            }else{
                partSize = mFileLength / 5000;
                partNumber = 5000;
            }
        }
        partAttr[0] = (int) partSize;
        partAttr[1] = partNumber;
    }

    @Override
    protected ResumableUploadResult doMultipartUpload() throws IOException, ClientException, ServiceException, InterruptedException {

        long tempUploadedLength = mUploadedLength;

        checkCancel();

        int[] partAttr = new int[2];
        checkPartSize(partAttr);
        int readByte = partAttr[0];
        final int partNumber = partAttr[1];

        if (mPartETags.size() > 0 && mAlreadyUploadIndex.size() > 0) { //revert progress
            if (mUploadedLength > mFileLength) {
                throw new ClientException("The uploading file is inconsistent with before");
            }

            if (mFirstPartSize > 0 && mFirstPartSize != readByte && mFirstPartSize < mFileLength) {
                throw new ClientException("The part size setting is inconsistent with before");
            }

            long revertUploadedLength = mUploadedLength;

            if (!TextUtils.isEmpty(mSp.getStringValue(mUploadId))) {
                revertUploadedLength = Long.valueOf(mSp.getStringValue(mUploadId));
            }

            if (mProgressCallback != null) {
                mProgressCallback.onProgress(mRequest, revertUploadedLength, mFileLength);
            }

            mSp.removeKey(mUploadId);
        }

        for (int i = 0; i < partNumber; i++) {

            if (mAlreadyUploadIndex.size() != 0 && mAlreadyUploadIndex.contains(i + 1)) {
                continue;
            }

            if (mPoolExecutor != null) {
                //need read byte
                if (i == partNumber - 1) {
                    readByte = (int) Math.min(readByte, mFileLength - tempUploadedLength);
                }
                final int byteCount = readByte;
                final int readIndex = i;
                tempUploadedLength += byteCount;
                mPoolExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        uploadPart(readIndex, byteCount, partNumber);
                    }
                });
            }
        }

        if (checkWaitCondition(partNumber)) {
            synchronized (mLock) {
                mLock.wait();
            }
        }

        checkException();
        //complete sort
        CompleteMultipartUploadResult completeResult = completeMultipartUploadResult();
        ResumableUploadResult result = null;
        if (completeResult != null) {
            result = new ResumableUploadResult(completeResult);
        }
        if (mRecordFile != null) {
            mRecordFile.delete();
        }
        if (mCRC64RecordFile != null) {
            mCRC64RecordFile.delete();
        }

        releasePool();
        return result;
    }

    @Override
    protected void checkException() throws IOException, ServiceException, ClientException {
        if (mContext.getCancellationHandler().isCancelled()) {
            if (mRequest.deleteUploadOnCancelling()) {
                abortThisUpload();
                if (mRecordFile != null) {
                    mRecordFile.delete();
                }
            } else {
                if (mPartETags != null && mPartETags.size() > 0 && mCheckCRC64 && mRequest.getRecordDirectory() != null) {
                    Map<Integer, Long> maps = new HashMap<Integer, Long>();
                    for (PartETag eTag : mPartETags) {
                        maps.put(eTag.getPartNumber(), eTag.getCRC64());
                    }
                    ObjectOutputStream oot = null;
                    try {
                        String filePath = mRequest.getRecordDirectory() + File.separator + mUploadId;
                        mCRC64RecordFile = new File(filePath);
                        if (!mCRC64RecordFile.exists()) {
                            mCRC64RecordFile.createNewFile();
                        }
                        oot = new ObjectOutputStream(new FileOutputStream(mCRC64RecordFile));
                        oot.writeObject(maps);
                    } catch (IOException e) {
                        OSSLog.logThrowable2Local(e);
                    } finally {
                        if (oot != null) {
                            oot.close();
                        }
                    }
                }
            }
        }
        super.checkException();
    }

    @Override
    protected void abortThisUpload() {
        if (mUploadId != null) {
            AbortMultipartUploadRequest abort = new AbortMultipartUploadRequest(
                    mRequest.getBucketName(), mRequest.getObjectKey(), mUploadId);
            mApiOperation.abortMultipartUpload(abort, null).waitUntilFinished();
        }
    }

    @Override
    protected void processException(Exception e) {
        synchronized (mLock) {
            mPartExceptionCount++;
            if (mUploadException == null || !e.getMessage().equals(mUploadException.getMessage())) {
                mUploadException = e;
            }
            OSSLog.logThrowable2Local(e);
            if (mContext.getCancellationHandler().isCancelled()) {
                if (!mIsCancel) {
                    mIsCancel = true;
                    mLock.notify();
                }
            }
        }
    }

    @Override
    protected void uploadPartFinish(PartETag partETag) throws Exception {
        if (mContext.getCancellationHandler().isCancelled()) {
            if (!mSp.contains(mUploadId)) {
                mSp.setStringValue(mUploadId, String.valueOf(mUploadedLength));
                onProgressCallback(mRequest, mUploadedLength, mFileLength);
            }
        }
    }
}
