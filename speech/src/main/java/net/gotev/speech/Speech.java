package net.gotev.speech;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Helper class to easily work with Android speech recognition.
 *
 * @author Aleksandar Gotev
 */
public class Speech {

    private static final String LOG_TAG = Speech.class.getSimpleName();

    private static Speech instance = null;

    private SpeechRecognizer mSpeechRecognizer;
    private String mCallingPackage;
    private boolean mPreferOffline = false;
    private boolean mGetPartialResults = true;
    private SpeechDelegate mDelegate;
    private boolean mIsListening = false;

    private List<String> mPartialData = new ArrayList<>();
    private String mUnstableData;

    private DelayedOperation mDelayedStopListening;
    private Context mContext;

    private TextToSpeech mTextToSpeech;
    private Map<String, TextToSpeechCallback> mTtsCallbacks = new HashMap<>();
    private Locale mLocale = Locale.getDefault();
    private float mTtsRate = 1.0f;
    private float mTtsPitch = 1.0f;
    private long mStopListeningDelayInMs = 1400;
    private long mTransitionMinimumDelay = 1200;
    private long mLastActionTimestamp;

    private TextToSpeech.OnInitListener mTttsInitListener = new TextToSpeech.OnInitListener() {
        @Override
        public void onInit(int status) {
            switch (status) {
                case TextToSpeech.SUCCESS:
                    Logger.info(LOG_TAG, "TextToSpeech engine successfully started");
                    break;

                case TextToSpeech.ERROR:
                    Logger.error(LOG_TAG, "Error while initializing TextToSpeech engine!");
                    break;

                default:
                    Logger.error(LOG_TAG, "Unknown TextToSpeech status: " + status);
                    break;
            }
        }
    };

    private UtteranceProgressListener mTtsProgressListener = new UtteranceProgressListener() {
        @Override
        public void onStart(final String utteranceId) {
            final TextToSpeechCallback callback = mTtsCallbacks.get(utteranceId);

            if (callback != null) {
                new Handler(mContext.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onStart();
                    }
                });
            }
        }

        @Override
        public void onDone(final String utteranceId) {
            final TextToSpeechCallback callback = mTtsCallbacks.get(utteranceId);

            if (callback != null) {
                new Handler(mContext.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onCompleted();
                        mTtsCallbacks.remove(utteranceId);
                    }
                });
            }
        }

        @Override
        public void onError(final String utteranceId) {
            final TextToSpeechCallback callback = mTtsCallbacks.get(utteranceId);

            if (callback != null) {
                new Handler(mContext.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        callback.onError();
                        mTtsCallbacks.remove(utteranceId);
                    }
                });
            }
        }
    };

    private RecognitionListener mListener = new RecognitionListener() {

        @Override
        public void onReadyForSpeech(Bundle bundle) {
            mPartialData.clear();
            mUnstableData = null;
        }

        @Override
        public void onBeginningOfSpeech() {
            mDelayedStopListening.start(new DelayedOperation.Operation() {
                @Override
                public void onDelayedOperation() {
                    returnPartialResultsAndRecreateSpeechRecognizer();
                }

                @Override
                public boolean shouldExecuteDelayedOperation() {
                    return true;
                }
            });
        }

        @Override
        public void onRmsChanged(float v) {
            mDelegate.onSpeechRmsChanged(v);
        }

        @Override
        public void onPartialResults(Bundle bundle) {
            mDelayedStopListening.resetTimer();

            List<String> partialResults = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            List<String> unstableData = bundle.getStringArrayList("android.speech.extra.UNSTABLE_TEXT");

            if (partialResults != null && !partialResults.isEmpty()) {
                mPartialData.clear();
                mPartialData.addAll(partialResults);
                mUnstableData = unstableData != null && !unstableData.isEmpty()
                        ? unstableData.get(0) : null;
                mDelegate.onSpeechPartialResults(partialResults);
            }
        }

        @Override
        public void onResults(Bundle bundle) {
            mDelayedStopListening.cancel();

            List<String> results = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);

            String result;

            if (results != null && !results.isEmpty()
                    && results.get(0) != null && !results.get(0).isEmpty()) {
                result = results.get(0);
            } else {
                Logger.info(Speech.class.getSimpleName(), "No speech results, getting partial");
                result = getPartialResultsAsString();
            }

            mIsListening = false;
            mDelegate.onSpeechResult(result);
        }

        @Override
        public void onError(int code) {
            Logger.error(LOG_TAG, "Speech recognition error", new SpeechRecognitionException(code));
            returnPartialResultsAndRecreateSpeechRecognizer();
        }

        @Override
        public void onBufferReceived(byte[] bytes) {

        }

        @Override
        public void onEndOfSpeech() {
        }

        @Override
        public void onEvent(int i, Bundle bundle) {

        }
    };

    private Speech(Context context) {
        commonInitializer(context);
    }

    private Speech(Context context, String callingPackage) {
        commonInitializer(context);
        mCallingPackage = callingPackage;
    }

    private void commonInitializer(Context context) {
        if (context == null)
            throw new IllegalArgumentException("context must be defined!");

        mContext = context;

        if (mTextToSpeech == null) {
            mTextToSpeech = new TextToSpeech(context, mTttsInitListener);
            mTextToSpeech.setOnUtteranceProgressListener(mTtsProgressListener);
        }

        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            if (mSpeechRecognizer != null) {
                mSpeechRecognizer.destroy();
            }

            mSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context);
            mSpeechRecognizer.setRecognitionListener(mListener);
            initDelayedStopListening(context);

        } else {
            mSpeechRecognizer = null;
        }

        mPartialData.clear();
        mUnstableData = null;
    }

    private void initDelayedStopListening(Context context) {
        if (mDelayedStopListening != null) {
            mDelayedStopListening.cancel();
            mDelayedStopListening = null;
        }

        mDelayedStopListening = new DelayedOperation(context, "delayStopListening", mStopListeningDelayInMs);
    }

    /**
     * Initializes speech recognition.
     *
     * @param context application context
     * @return speech instance
     */
    public static Speech init(Context context) {
        if (instance == null) {
            instance = new Speech(context);
        }

        return instance;
    }

    /**
     * Initializes speech recognition.
     *
     * @param context application context
     * @param callingPackage The extra key used in an intent to the speech recognizer for
     *                       voice search. Not generally to be used by developers.
     *                       The system search dialog uses this, for example, to set a calling
     *                       package for identification by a voice search API.
     *                       If this extra is set by anyone but the system process,
     *                       it should be overridden by the voice search implementation.
     *                       By passing null or empty string (which is the default) you are
     *                       not overriding the calling package
     * @return speech instance
     */
    public static Speech init(Context context, String callingPackage) {
        if (instance == null) {
            instance = new Speech(context, callingPackage);
        }

        return instance;
    }

    /**
     * Gets speech recognition instance.
     * @return SpeechRecognition instance
     */
    public static Speech getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Speech recognition has not been initialized! call init method first!");
        }

        return instance;
    }

    /**
     * Starts voice recognition.
     * @param delegate delegate which will receive speech recognition events and status
     * @throws SpeechRecognitionNotAvailable when speech recognition is not available on the device
     */
    public void startListening(SpeechDelegate delegate)
            throws SpeechRecognitionNotAvailable{
        if (mIsListening) return;

        if (mSpeechRecognizer == null)
            throw new SpeechRecognitionNotAvailable();

        if (delegate == null)
            throw new IllegalArgumentException("delegate must be defined!");

        if (throttleAction()) {
            Logger.debug(getClass().getSimpleName(), "Hey man calm down! Throttling start to prevent disaster!");
            return;
        }

        mDelegate = delegate;

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, mGetPartialResults)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, mLocale.getLanguage())
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);

        if (mCallingPackage != null && !mCallingPackage.isEmpty()) {
            intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, mCallingPackage);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, mPreferOffline);
        }

        mSpeechRecognizer.startListening(intent);
        mIsListening = true;
        updateLastActionTimestamp();
        mDelegate.onStartOfSpeech();

    }

    private void updateLastActionTimestamp() {
        mLastActionTimestamp = new Date().getTime();
    }

    private boolean throttleAction() {
        return (new Date().getTime() <= (mLastActionTimestamp + mTransitionMinimumDelay));
    }

    /**
     * Stops voice recognition listening.
     * This method does nothing if voice listening is not active
     */
    public void stopListening() {
        if (!mIsListening) return;

        if (throttleAction()) {
            Logger.debug(getClass().getSimpleName(), "Hey man calm down! Throttling stop to prevent disaster!");
            return;
        }

        mIsListening = false;
        updateLastActionTimestamp();
        returnPartialResultsAndRecreateSpeechRecognizer();
    }

    private String getPartialResultsAsString() {
        StringBuilder out = new StringBuilder("");

        for (String partial : mPartialData) {
            out.append(partial).append(" ");
        }

        if (mUnstableData != null && !mUnstableData.isEmpty())
            out.append(mUnstableData);

        return out.toString();
    }

    private void returnPartialResultsAndRecreateSpeechRecognizer() {
        mIsListening = false;
        mDelegate.onSpeechResult(getPartialResultsAsString());

        // recreate the speech recognizer
        commonInitializer(mContext);
    }

    /**
     * Check if voice recognition is currently active.
     * @return true if the voice recognition is on, false otherwise
     */
    public boolean isListening() {
        return mIsListening;
    }

    /**
     * Uses text to speech to transform a written message into a sound.
     * @param message message to play
     */
    public void say(String message) {
        say(message, null);
    }

    /**
     * Uses text to speech to transform a written message into a sound.
     * @param message message to play
     * @param callback callback which will receive progress status of the operation
     */
    public void say(String message, TextToSpeechCallback callback) {
        mTextToSpeech.setLanguage(mLocale);
        mTextToSpeech.setPitch(mTtsPitch);
        mTextToSpeech.setSpeechRate(mTtsRate);

        String utteranceId = UUID.randomUUID().toString();

        if (callback != null) {
            mTtsCallbacks.put(utteranceId, callback);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            mTextToSpeech.speak(message, TextToSpeech.QUEUE_ADD, null, utteranceId);
        } else {
            HashMap<String, String> params = new HashMap<>();
            params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
            mTextToSpeech.speak(message, TextToSpeech.QUEUE_ADD, params);
        }
    }

    /**
     * Stops text to speech.
     */
    public void stopTextToSpeech() {
        if (mTextToSpeech != null) {
            mTextToSpeech.stop();
        }
    }

    /**
     * Set whether to only use an offline speech recognition engine.
     * The default is false, meaning that either network or offline recognition engines may be used.
     * @param preferOffline true to prefer offline engine, false to use either one of the two
     * @return speech instance
     */
    public Speech setPreferOffline(boolean preferOffline) {
        mPreferOffline = preferOffline;
        return this;
    }

    /**
     * Set whether partial results should be returned by the recognizer as the user speaks
     * (default is true). The server may ignore a request for partial results in some or all cases.
     * @param getPartialResults true to get also partial recognition results, false otherwise
     * @return speech instance
     */
    public Speech setGetPartialResults(boolean getPartialResults) {
        mGetPartialResults = getPartialResults;
        return this;
    }

    public Speech setLocale(Locale locale) {
        mLocale = locale;
        return this;
    }

    public Speech setTextToSpeechRate(float rate) {
        mTtsRate = rate;
        return this;
    }

    public Speech setTextToSpeechPitch(float pitch) {
        mTtsPitch = pitch;
        return this;
    }

    public Speech setStopListeningAfterInactivity(long milliseconds) {
        mStopListeningDelayInMs = milliseconds;
        initDelayedStopListening(mContext);
        return this;
    }

    public Speech setTransitionMinimumDelay(long milliseconds) {
        mTransitionMinimumDelay = milliseconds;
        return this;
    }

}
