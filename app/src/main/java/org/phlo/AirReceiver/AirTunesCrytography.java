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

import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.KeySpec;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;

public final class AirTunesCrytography {
	/**
	 * Class is not meant to be instantiated
	 */
	private AirTunesCrytography() {
		throw new RuntimeException();
	}

	/**
	 * The AirTunes private key in PEM-encoded PKCS#8 format.
	 * Original Key from shairport was in PEM-encoded PKCS#1 format
	 */
	private static final String PrivateKeyData =
		"MIIEvwIBADANBgkqhkiG9w0BAQEFAASCBKkwggSlAgEAAoIBAQDn10TyouJ4i2wf\n" +
		"VaCOtwVEqPp5RaqL5sYs5fUcvdTcaEL+PRCD3S7ewb/UJS3ALm85i98OYUjqhIVe\n" +
		"LkQtptYmZPZ0ofMEkpreT2iT7y325xGox3oNkcnZgIIuUNEpIq/qQOqfDhTA92k4\n" +
		"xfOIL8AyPdn+VRVfUbtZIcIBYp/XM1LV4u+qv5ugSNe4E6K2dn9sPM8etM5nPQN7\n" +
		"DS6jDF//6wb40Ird5AlXGpxon+8QcohV3Yz7movvXIlD7ztfqhXd5pi+3fNZlgPr\n" +
		"Pm9hNyu2KPZVn1maeL9QBoeqf0l2wFYtQSlW+JieGKY1W9gVl4JeD8h1ND7HghF2\n" +
		"Jc2/mER7AgMBAAECggEBAOXwDHL1d9YEuaTOQSKqhLAXQ+yZWs/Mf0qyfAsYf5Bm\n" +
		"W+NZ3xJZgY3u7XnTse+EXk3d2smhVTc7XicNjhMVABouUn1UzfkACldovJjURGs3\n" +
		"u70Asp3YtTBiEzsqbnf07jJQViKQTacg+xwSwDmW2nE6BQYJjtvt7Pk20PqcvVkp\n" +
		"q7Dto1eZUC+YlNy4/FaaiS0XeAMkorbDFm40ZwkTS4VAQbhncGtY/vKg25Ird2KL\n" +
		"aOaWk8evQ78qc9C3Mjd6C6F7RPBR6b95hJ3LMzJXH9inCTPC1gvexHmTSj2spAu2\n" +
		"8vN8Cp0HEG6tyLNpoD8vQciACY6K3UYkDaxozFNU82ECgYEA9+C/Wh5nGDGai2IJ\n" +
		"wxcURARZ+XOFZhOxeuFQi7PmMW5rf0YtL31kQSuEt2vCPysMNWJFUnmyQ6n3MW+V\n" +
		"gAezTGH3aOLUTtX/KycoF+wys+STkpIo+ueOd0yg9169adWSAnmPEW42DGQ4sy4b\n" +
		"2LncHjIy8NMJGIg8xD743aIsNpECgYEA72//+ZTx5WRBqgA1/RmgyNbwI3jHBYDZ\n" +
		"xIQgeR30B8WR+26/yjIsMIbdkB/S+uGuu2St9rt5/4BRvr0M2CCriYdABgGnsv6T\n" +
		"kMrMmsq47Sv5HRhtj2lkPX7+D11W33V3otA16lQT/JjY8/kI2gWaN52kscw48V1W\n" +
		"CoPMMXFTyEsCgYEA0OuvvEAluoGMdXAjNDhOj2lvgE16oOd2TlB7t9Pf78fWeMZo\n" +
		"LT+tcTRBvurnJKCewJvcO8BwnJEz1Ins4qUa3QUxJ0kPkobRc8ikBU3CCldcfkwM\n" +
		"mDT0od6HSRej5ADq+IUGLbXLfjQ2iecR91/ng9fhkZL9dpzVQr6kuQEH7NECgYB/\n" +
		"QBjcfeopLaUwQjhvMQWgd4rcbz3mkNordMUFWYPt9XRmGi/Xt96AU8zA4gjwyKxi\n" +
		"b1l9PZnSzlGjezmuS36e8sB18L89g8rNMtqWkZLCiZI1glwH0c0yWaGQbNzUmcth\n" +
		"PiLJTLHqlxkGYJ3xsPSLBj8XNyA0NpSZtf35cO9EDQKBgQCQTukg+UTvWq98lCCg\n" +
		"D16bSAgsC4Tg+7XdoqImd9+3uEiNsr7mTJvdPKxm+jIOdvcc4q8icru9dsq5TghK\n" +
		"DEHZsHcdxjNAwazPWonaAbQ3mG8mnPDCFuFeoUoDjNppKvDrbbAOeIArkyUgTS0g\n" +
		"Aoo/jLE0aOgPZBiOEEa6G+RYpg==\n" +
		"";

	/**
	 * The AirTunes private key as an instance of {@link java.security.interfaces.RSAPrivateKey}
	 */
	public static final RSAPrivateKey PrivateKey = rsaPrivateKeyDecode(PrivateKeyData);

	/**
	 *
	 * @param transformation the transformation to find an implementation for
	 */
	public static Cipher getCipher(final String transformation) {
		try {
			return Cipher.getInstance(transformation);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("No such algorithm: " + transformation, e);
		} catch (NoSuchPaddingException e) {
			throw new RuntimeException("No such padding: " + transformation, e);
		}
	}

	/**
	 * Converts a PEM-encoded PKCS#8 private key into an RSAPrivateKey instance
	 * useable with JCE
	 *
	 * @param privateKey private key in PKCS#8 format and PEM-encoded
	 * @return RSAPrivateKey instance containing the key
	 */
	private static RSAPrivateKey rsaPrivateKeyDecode(final String privateKey) {
		try {
			final KeyFactory keyFactory = KeyFactory.getInstance("RSA");
			final KeySpec ks = new PKCS8EncodedKeySpec(Base64.decodePadded(privateKey));
			return (RSAPrivateKey)keyFactory.generatePrivate(ks);
		}
		catch (final Exception e) {
			throw new RuntimeException("Failed to decode built-in private key", e);
		}
	}
}
