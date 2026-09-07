package io.flutter.plugins.videoplayer;

import android.content.Context;
import android.util.Log;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.annotation.VisibleForTesting;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelector;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.NoSuchAlgorithmException;
import java.security.KeyManagementException;

import io.flutter.view.TextureRegistry;
// import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;

public class PlayerUtil {

    private static OkHttpClient client;

    public static OkHttpClient getUnsafeClient(String host, int port) {
        try {
            // 创建一个不验证证书链的 TrustManager
            final TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[]{};
                        }
                    }
            };

            // 安装全信任的 TrustManager
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());

            // 创建一个全信任的 SSL 套接字工厂
            final javax.net.ssl.SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            builder.sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0]);
            builder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port)));
            builder.hostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });

            return builder.build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException(e);
        }
    }

    public static void setupVideo(ExoPlayer.Builder builder, HttpVideoAsset asset, Context context) {
        /**
         * 增加软解码器支持（集成 FFmpeg 音频扩展，支持 DTS/TrueHD 等格式）
         */
        NextRenderersFactory renderersFactory = new NextRenderersFactory(context);
        renderersFactory.setEnableDecoderFallback(true);
        renderersFactory.setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON);
        builder.setRenderersFactory(renderersFactory);

        DefaultTrackSelector trackSelector = new DefaultTrackSelector(context);
        // 获取当前的 TrackSelector
        trackSelector.setParameters(
                trackSelector.buildUponParameters()
                        .setPreferredTextLanguage(null) // 不设置特定语言偏好，让系统自动选择
                        .setSelectUndeterminedTextLanguage(true) // 选择未确定语言的字幕
                        .setIgnoredTextSelectionFlags(0) // 不忽略任何文本选择标志
                        .setForceHighestSupportedBitrate(false) // 不强制最高比特率
                        .setMaxVideoSize(Integer.MAX_VALUE, Integer.MAX_VALUE) // 不限制视频尺寸
                        .setRendererDisabled(C.TRACK_TYPE_TEXT, false)); // 启用字幕渲染器

        builder.setTrackSelector(trackSelector);

        String DNS = null;
        HttpVideoAsset httpVideoAsset = (HttpVideoAsset) asset;
        Map<String, String> httpHeaders = httpVideoAsset.getHttpHeaders();
        final String userAgent = httpHeaders != null && httpHeaders.containsKey("user-agent") ? httpHeaders.get("user-agent") : null;
        if (asset.assetUrl.startsWith("http") && httpHeaders != null && !httpHeaders.isEmpty()) {
            Log.d("EXO", "HttpVideoAsset" + httpHeaders);
            OkHttpClient.Builder httpBuilder = new OkHttpClient.Builder();
            if (httpHeaders.containsKey("http-proxy")) {
                String[] split = httpHeaders.get("http-proxy").split(":");
                if (split.length == 2) {
                    String proxy = split[0];
                    int port = Integer.parseInt(split[1]);
                    httpBuilder.proxy(new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxy, port)))
                            .build();
                    client = getUnsafeClient(split[0], Integer.parseInt(split[1]));
                    Log.d("EXO", "getUnsafeClient");
                }
            }
            if (client == null) {
                client = httpBuilder.build();
            }

            builder.setMediaSourceFactory(new DefaultMediaSourceFactory(
                    new DataSource.Factory() {
                        @Override
                        public DataSource createDataSource() {
                            OkHttpDataSource.Factory factory = new OkHttpDataSource.Factory(
                                    new Call.Factory() {
                                        @NotNull
                                        @Override
                                        public Call newCall(@NotNull Request request) {
                                            return client.newCall(request);
                                        }
                                    }
                            );
                            if (userAgent != null) {
                                factory.setUserAgent(userAgent);
                            }

                            factory.setDefaultRequestProperties(httpHeaders);
                            return factory.createDataSource();

                        }
                    }
            ));
        }

    }
}