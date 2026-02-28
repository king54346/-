package ai.guiji.duix.test.render;

import ai.guiji.duix.sdk.client.bean.ImageFrame;
import ai.guiji.duix.sdk.client.render.RenderSink;

public class DebugSink implements RenderSink {

    VideoFrameCallback callback;

    public DebugSink(VideoFrameCallback callback){
        this.callback = callback;
    }

    @Override
    public void onVideoFrame(ImageFrame imageFrame) {
        if (callback != null){
            callback.onVideoFrame(imageFrame);
        }
    }

    public interface VideoFrameCallback{
        void onVideoFrame(ImageFrame imageFrame);
    }
}
