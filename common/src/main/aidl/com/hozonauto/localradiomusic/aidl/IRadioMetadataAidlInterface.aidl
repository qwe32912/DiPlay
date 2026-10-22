package com.hozonauto.localradiomusic.aidl;

// Publishing port of the Hozon media hub (of.mediaserver.hz), declared by its
// MediaService for the intent action "of.radio.hz.aidl.IRadioImgAidlInterface".
// Method order is the on-wire contract and must not change:
//   transaction 1 = switchLocalRadioPlayImg
//   transaction 2 = of_updateLocalRadioMetadata
// duration and progress are milliseconds; the hub forwards them unscaled.
interface IRadioMetadataAidlInterface {
    void switchLocalRadioPlayImg(in byte[] img);
    void of_updateLocalRadioMetadata(String sourcetype, String name1, String name2,
        int duration, int progress, boolean playState, int repeatMode, in byte[] img);
}
