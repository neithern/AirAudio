/*
 * This file is part of AirReceiver.
 *
 * AirReceiver is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * AirReceiver is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with AirReceiver.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.phlo.AirReceiver;

import android.media.AudioTrack;
import android.os.Build;

import org.jboss.netty.buffer.ChannelBuffer;

import java.util.concurrent.ConcurrentSkipListMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Audio output queue.
 * 
 * Serves an an {@link AudioClock} and allows samples to be queued
 * for playback at a specific time.
 */
public class AudioOutputQueue implements AudioClock {
	private static Logger s_logger = Logger.getLogger("AudioOutputQueue");

	private static final double TimeSince1970 = 2208988800.0;

	private static final double QueueLengthMaxSeconds = 10;
	private static final double BufferSizeSeconds = 0.05;
	private static final double TimingPrecision = 0.001;

	/**
	 * Signals that the queue is being closed.
	 * Never transitions from true to false!
	 */
	private volatile boolean m_closing = false;

	/**
	 *  The line's audio format
	 */
	private final AudioFormat m_format;

	/**
	 * Audio output channel mode
	 */
	private final AudioChannel m_channelMode;

	/**
	 * Bytes per frame, i.e. number of bytes
	 * per sample times the number of channels
	 */
	private final int m_bytesPerFrame;

	/**
	 * Sample rate
	 */
	private final double m_sampleRate;

	/**
	 * Average packet size in frames.
	 * We use this as the number of silence frames
	 * to write on a queue underrun
	 */
	private final int m_packetSizeFrames;

	/**
	 * Android audio track (replaces the SourceDataLine)
	 */
	private AudioTrack m_audioTrack;

	/**
	 * The prepared silence data
	 */
	private final byte[] m_silenceFrames;

	/**
	 * Packet queue, indexed by playback time
	 */
	private final ConcurrentSkipListMap<Long, ChannelBuffer> m_queue = new ConcurrentSkipListMap<>();

	/**
	 * Enqueuer thread
	 */
	private final Thread m_queueThread = new Thread(new EnQueuer());

	/**
	 * Number of frames appended to the line
	 */
	private long m_lineFramesWritten = 0;

	/**
	 * Largest frame time seen so far
	 */
	private long m_latestSeenFrameTime = 0;

	/**
	 * The frame time corresponding to line time zero
	 */
	private long m_frameTimeOffset = 0;

	/**
	 * The seconds time corresponding to line time zero
	 */
	private double m_secondsTimeOffset;

	/**
	 * Requested line gain
	 */
	private float m_requestedGain = 0.0f;
	private float m_trackVolume = 0.0f;
	private static final float MUTE_VOLUME = 0.0f;

	/**
	 * Enqueuer thread
	 */
	private class EnQueuer implements Runnable {
		/**
		 * Enqueuer thread main method
		 */
		@Override
		public void run() {
			try {
				/* Mute line initially to prevent clicks */
				setLineGain(MUTE_VOLUME);

				/* Start the line */
				m_audioTrack.play();

				boolean lineMuted = true;
				boolean didWarnGap = false;
				while (!m_closing) {
					if (!m_queue.isEmpty()) {
						/* Queue filled */

						/* If the gap between the next packet and the end of line is
						 * negligible (less than one packet), we write it to the line.
						 * Otherwise, we fill the line buffer with silence and hope for
						 * further packets to appear in the queue
						 */
						final long entryFrameTime = m_queue.firstKey();
						final long entryLineTime = convertFrameToLineTime(entryFrameTime);
						final long gapFrames = entryLineTime - getNextLineTime();
						if (gapFrames < -m_packetSizeFrames) {
							/* Too late for playback */
							s_logger.warning("Audio data was scheduled for playback " + (-gapFrames) + " frames ago, skipping");

							m_queue.remove(entryFrameTime);
							continue;
						}
						else if (gapFrames < m_packetSizeFrames) {
							/* Negligible gap between packet and line end. Prepare packet for playback */
							didWarnGap = false;

							/* Unmute line in case it was muted previously */
							if (lineMuted) {
								s_logger.info("Audio data available, un-muting line");

								lineMuted = false;
								applyGain();
							}
							else if (getLineGain() != m_requestedGain) {
								applyGain();
							}

							/* Get sample data and do sanity checks */
							final ChannelBuffer buffer = m_queue.remove(entryFrameTime);
							int nextPlaybackSamplesLength = buffer.capacity();
							if (nextPlaybackSamplesLength % m_bytesPerFrame != 0) {
								s_logger.severe("Audio data contains non-integral number of frames, ignore last " + (nextPlaybackSamplesLength % m_bytesPerFrame) + " bytes");

								nextPlaybackSamplesLength -= nextPlaybackSamplesLength % m_bytesPerFrame;
							}

							/* Append packet to line */
							s_logger.finest("Audio data containing " + nextPlaybackSamplesLength / m_bytesPerFrame + " frames for playback time " + entryFrameTime + " found in queue, appending to the output line");
							appendFrames(buffer.array(), buffer.arrayOffset(), nextPlaybackSamplesLength, entryLineTime);
							continue;
						}
						else {
							/* Gap between packet and line end. Warn */

							if (!didWarnGap) {
								didWarnGap = true;
								s_logger.warning("Audio data missing for frame time " + getNextLineTime() + " (currently " + gapFrames + " frames), writing " + m_packetSizeFrames + " frames of silence");
							}
						}
					}
					else {
						/* Queue empty */

						if (!lineMuted) {
							lineMuted = true;
							setLineGain(MUTE_VOLUME);
							s_logger.fine("Audio data ended at frame time " + getNextLineTime() + ", writing " + m_packetSizeFrames + " frames of silence and muted line");
						}
					}

					appendSilence(m_packetSizeFrames);
				}

				/* Before we exit, we fill the line's buffer with silence. This should prevent
				 * noise from being output while the line is being stopped
				 */
				// Don't need the appendSilence in Android?
				// appendSilence(m_audioTrack.available() / m_bytesPerFrame);
			}
			catch (final Throwable e) {
				s_logger.log(Level.SEVERE, "Audio output thread died unexpectedly", e);
			}
			finally {
				setLineGain(MUTE_VOLUME);
				m_audioTrack.stop();
				m_audioTrack.release();
			}
		}

		/**
		 * Append the range [off,off+len) from the provided sample data to the line.
		 * If the requested playback time does not match the line end time, samples are
		 * skipped or silence is inserted as necessary. If the data is marked as being
		 * just a filler, some warnings are suppressed.
		 *
		 * @param samples sample data
		 * @param len sample data length
		 * @param lineTime playback time
		 */
		private void appendFrames(final byte[] samples, int off, final int len, long lineTime) {
			//assert off % m_bytesPerFrame == 0;
			//assert len % m_bytesPerFrame == 0;

			while (!m_closing) {
				/* Fetch line end time only once per iteration */
				final long endLineTime = getNextLineTime();

				final long timingErrorFrames = lineTime - endLineTime;
				final double timingErrorSeconds = timingErrorFrames / m_sampleRate;

				if (Math.abs(timingErrorSeconds) <= TimingPrecision) {
					/* Samples to append scheduled exactly at line end. Just append them and be done */

					appendFrames(samples, off, len);
					break;
				}
				else if (timingErrorFrames > 0) {
					/* Samples to append scheduled after the line end. Fill the gap with silence */
					//s_logger.warning("Audio output non-continous (gap of " + timingErrorFrames + " frames), filling with silence");

					appendSilence((int)(lineTime - endLineTime));
				}
				else if (timingErrorFrames < 0) {
					/* Samples to append scheduled before the line end. Remove the overlapping
					 * part and retry
					 */
					//s_logger.warning("Audio output non-continous (overlap of " + (-timingErrorFrames) + "), skipping overlapping frames");

					off += (endLineTime - lineTime) * m_bytesPerFrame;
					lineTime += endLineTime - lineTime;
					if (off + len >= samples.length)
						off = samples.length - len;
				}
				else {
					/* Strange universe... */
					//assert false;
				}
			}
		}

		private void appendSilence(int frames) {
			while (frames > 0 && !m_closing) {
				final int length = Math.min(frames, m_packetSizeFrames) * m_bytesPerFrame;
				appendFrames(m_silenceFrames, 0, length);
				frames -= m_packetSizeFrames;
			}
		}

		/**
		 * Append the range [off,off+len) from the provided sample data to the line.
		 *
		 * @param samples sample data
		 * @param off sample data offset
		 * @param len sample data length
		 */
		private void appendFrames(final byte[] samples, int off, int len) {
			//assert off % m_bytesPerFrame == 0;
			//assert len % m_bytesPerFrame == 0;

			/* Make sure that [off,off+len) does not exceed sample's bounds */
			off = Math.min(off, (samples != null) ? samples.length : 0);
			len = Math.min(len, (samples != null) ? samples.length - off : 0);
			if (len <= 0)
				return;

			/* Convert samples if necessary */
			if (m_bytesPerFrame == 4) {
				if (m_channelMode == AudioChannel.ONLY_LEFT) {
					for (int i = off; i < off + len; i += m_bytesPerFrame) {
						samples[i + 2] = samples[i];
						samples[i + 3] = samples[i + 1];
					}
				} else if (m_channelMode == AudioChannel.ONLY_RIGHT) {
					for (int i = off; i < off + len; i += m_bytesPerFrame) {
						samples[i] = samples[i + 2];
						samples[i + 1] = samples[i + 3];
					}
				}
			}

			/* Write samples to audio track */
			int bytesWritten = 0;
			while (len > 0 && !m_closing) {
				final int ret = m_audioTrack.write(samples, off, len);
				if (ret < 0)
					s_logger.warning("Audio track written error: " + ret + " of " + len + " bytes");
				else if (ret != len)
					s_logger.warning("Audio track accepted only " + ret + " bytes of " + len + " bytes");
				if (ret > 0) {
					off += ret;
					len -= ret;
					bytesWritten += ret;
				}
			}

			/* Update state */
			synchronized(AudioOutputQueue.this) {
				m_lineFramesWritten += bytesWritten / m_bytesPerFrame;
				s_logger.finest("Audio track end is now at " + getNextLineTime() + " after writing " + len / m_bytesPerFrame + " frames");
			}
		}
	}

	AudioOutputQueue(final AudioStreamInformationProvider streamInfoProvider, int streamType, AudioChannel channelMode) {
		final AudioFormat audioFormat = streamInfoProvider.getAudioFormat();

		m_format = audioFormat;
		m_channelMode = channelMode;

		/* Audio format-dependent stuff */
		m_packetSizeFrames = streamInfoProvider.getFramesPerPacket();
		m_bytesPerFrame = m_format.getChannels() * m_format.getSampleSizeInBits() / 8;
		m_sampleRate = m_format.getSampleRate();
		m_silenceFrames = new byte[m_packetSizeFrames * m_bytesPerFrame];
		for(int b=0; b < m_silenceFrames.length; ++b)
			m_silenceFrames[b] = (b % 2 == 0) ? (byte)-128 : (byte)0;

		/* Compute desired line buffer size and obtain a line */
		final int desiredBufferSize = (int)Math.pow(2, Math.ceil(Math.log(BufferSizeSeconds * m_sampleRate * m_bytesPerFrame) / Math.log(2.0)));
		m_audioTrack = new AudioTrack(streamType,
				m_format.getSampleRate(),
				android.media.AudioFormat.CHANNEL_OUT_STEREO,
				android.media.AudioFormat.ENCODING_PCM_16BIT,
				desiredBufferSize,
				AudioTrack.MODE_STREAM);
		s_logger.info("Audio track created of type " + streamType +  ". Requested buffer of " + desiredBufferSize / m_bytesPerFrame  + " frames, got " + (desiredBufferSize / m_bytesPerFrame) + " frames");

		/* Start enqueuer thread and wait for the line to start.
		 * The wait guarantees that the AudioClock functions return
		 * sensible values right after construction
		 */
		m_queueThread.setDaemon(true);
		m_queueThread.setName("Audio Enqueuer");
		m_queueThread.setPriority(Thread.MAX_PRIORITY);
		/*m_queueThread.start();
		while (m_queueThread.isAlive() && !m_audioTrack.isActive())
			Thread.yield();*/

		/* Initialize the seconds time offset now that the line is running. */
		m_secondsTimeOffset = TimeSince1970 + System.currentTimeMillis() * 1e-3;
	}

	public void start(){
		m_queueThread.start();
		while (m_queueThread.isAlive() && m_audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING)
			Thread.yield();

		/* Initialize the seconds time offset now that the line is running. */
		m_secondsTimeOffset = TimeSince1970 + System.currentTimeMillis() * 1e-3;
	}

	/**
	 * Sets the line's MASTER_GAIN control to the provided value,
	 * or complains to the log of the line does not support a MASTER_GAIN control
	 *
	 * @param gain gain to set
	 */
	private void setLineGain(final float gain) {
		m_trackVolume = gain;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
			m_audioTrack.setVolume(gain);
		} else {
			m_audioTrack.setStereoVolume(gain, gain);
		}
	}

	/**
	 * Returns the line's MASTER_GAIN control's value.
	 */
	private float getLineGain() {
		return m_trackVolume;
	}

	private synchronized void applyGain() {
		setLineGain(m_requestedGain);
	}

	/**
	 * Sets the desired output gain.
	 *
	 * @param gain desired gain
	 */
	public synchronized void setGain(final float gain) {
		m_requestedGain = gain;
	}

	/**
	 * Returns the desired output gain.
	 */
	public synchronized float getGain() {
		return m_requestedGain;
	}

	/**
	 * Stops audio output
	 */
	public void close() {
		m_closing = true;
		m_queueThread.interrupt();
	}

	/**
	 * Adds sample data to the queue
	 *
	 * @param frameTime start time of sample data
	 * @param buffer sample data
	 * @return true if the sample data was added to the queue
	 */
	public synchronized boolean enqueue(final long frameTime, final ChannelBuffer buffer) {
		final int length = buffer.capacity();
		/* Playback time of packet */
		final double packetSeconds = (double)length / (m_bytesPerFrame * m_sampleRate);
		
		/* Compute playback delay, i.e., the difference between the last sample's
		 * playback time and the current line time
		 */
		final double delay =
			(convertFrameToLineTime(frameTime) + length / (double) m_bytesPerFrame - getNextLineTime()) /
			m_sampleRate;

		m_latestSeenFrameTime = Math.max(m_latestSeenFrameTime, frameTime);

		if (delay < -packetSeconds) {
			/* The whole packet is scheduled to be played in the past */
			s_logger.warning("Audio data arrived " + -(delay) + " seconds too late, dropping");
			return false;
		}
		else if (delay > QueueLengthMaxSeconds) {
			/* The packet extends further into the future that our maximum queue size.
			 * We reject it, since this is probably the result of some timing discrepancies
			 */
			s_logger.warning("Audio data arrived " + delay + " seconds too early, dropping");
			return false;
		}

		m_queue.put(frameTime, buffer);
		return true;
	}

	/**
	 * Removes all currently queued sample data
	 */
	public void flush() {
		m_queue.clear();
	}

	@Override
	public synchronized void setFrameTime(final long frameTime, final double secondsTime) {
		final double ageSeconds = getNowSecondsTime() - secondsTime;
		final long lineTime = Math.round((secondsTime - m_secondsTimeOffset) * m_sampleRate);

		final long frameTimeOffsetPrevious = m_frameTimeOffset;
		m_frameTimeOffset = frameTime - lineTime;

		s_logger.info("Time adjusted " + (m_frameTimeOffset - frameTimeOffsetPrevious) + " based on " + ageSeconds + " seconds old and " + (m_latestSeenFrameTime - frameTime) + " frames");
	}

	@Override
	public double getNowSecondsTime() {
		return m_secondsTimeOffset + getNowLineTime() / m_sampleRate;
	}

	@Override
	public long getNowFrameTime() {
		return m_frameTimeOffset + getNowLineTime();
	}

	@Override
	public double getNextSecondsTime() {
		return m_secondsTimeOffset + getNextLineTime() / m_sampleRate;
	}

	@Override
	public long getNextFrameTime() {
		return m_frameTimeOffset + getNextLineTime();
	}

	@Override
	public double convertFrameToSecondsTime(final long frameTime) {
		return m_secondsTimeOffset + (frameTime - m_frameTimeOffset) / m_sampleRate;
	}

	private synchronized long getNextLineTime() {
		return m_lineFramesWritten;
	}

	private long m_lastPosition = 0;
	private long m_totalPosition = 0;

	private synchronized long getNowLineTime() {
		if (m_audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
			int intPos = m_audioTrack.getPlaybackHeadPosition();
			long longPos = intPos & 0xffffffffL;
			if (longPos < m_lastPosition && longPos < 0x7fffffffL && m_lastPosition > 0x80000000L) {
				// wrap(overflow), need to fix it as 64 bits
				m_totalPosition += 0xffffffffL;
			}
			m_lastPosition = longPos;
			return m_totalPosition + longPos;
		}
		return 0;
	}

	private synchronized long convertFrameToLineTime(final long entryFrameTime) {
		return entryFrameTime - m_frameTimeOffset;
	}
}
