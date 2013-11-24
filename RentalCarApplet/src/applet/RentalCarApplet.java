/*
 * $Id: RentalCarApplet.java,v 0.1 2013/11/15 13:37:07$
 */
package applet;

import javacard.framework.APDU;
import javacard.framework.Applet;
import javacard.framework.ISO7816;
import javacard.framework.ISOException;
import javacard.framework.JCSystem;
import javacard.framework.SystemException;
import javacard.framework.Util;
import javacard.security.KeyBuilder;
import javacard.security.RSAPrivateKey;
import javacard.security.RSAPublicKey;
import javacard.security.Signature;
import javacardx.crypto.Cipher;

/**
 * Class RentalCarApplet.
 * 
 * @author Group ?
 * 
 * @version $Revision: 0.1 $
 */
public class RentalCarApplet extends Applet implements ISO7816 {
	private static final byte STATE_INIT = 0;
	private static final byte STATE_ISSUED = 1;

	/** CLA BYTES: values between 0xB0 and CF can be used */
	
	/** Issue Bytes */
	private static final byte CLA_ISSUE = (byte) 0xB1;
	private static final byte SET_PUBLIC_KEY_SIGNATURE = (byte) 0x01;
	private static final byte SET_PUBLIC_KEY_MODULUS_SC = (byte) 0x02;
	private static final byte SET_PUBLIC_KEY_EXPONENT_SC = (byte) 0x03;
	private static final byte SET_SC_ID = (byte) 0x04;
	private static final byte SET_PRVATE_KEY_MODULUS_SC = (byte) 0x05;
	private static final byte SET_PRIVATE_KEY_EXPONENT_SC = (byte) 0x06;
	private static final byte SET_PUBLIC_KEY_MODULUS_RT = (byte) 0x07;
	private static final byte SET_PUBLIC_KEY_EXPONENT_RT = (byte) 0x08;
	private static final byte SET_START_NONCE = (byte) 0x09;

	/** Init Bytes */
	private static final byte CLA_INIT = (byte) 0xB2;
	private static final byte INIT_START = (byte) 0x01;
	private static final byte INIT_AUTHENTICATED = (byte) 0x02;
	private static final byte INIT_SECOND_NONCE = (byte) 0x03;
	private static final byte INIT_SET_CAR_KEY_MODULUS = (byte) 0x04;
	private static final byte INIT_SET_CAR_KEY_EXPONENT = (byte) 0x05;
	private static final byte INIT_CHECK_CAR_KEY_SIGNATURE = (byte) 0x06;
	private static final byte INIT_SET_SIGNED_ENCRYPTED_CAR_DATA = (byte) 0x07;

	/** Read Bytes */
	private static final byte CLA_READ = (byte) 0xB3;
	private static final byte READ_MILEAGE_SIGNED_NONCE = (byte) 0x01;
	private static final byte READ_MILEAGE_START_MILEAGE = (byte) 0x02;
	private static final byte READ_MILEAGE_FINAL_MILEAGE = (byte) 0x03;

	/** Reset Bytes */
	private static final byte CLA_RESET = (byte) 0xB4;
	private static final byte RESET_CARD = (byte) 0x01;

	/** Start Bytes */
	private static final byte CLA_START = (byte) 0xB5;
	private static final byte SET_START_MILEAGE = (byte) 0x01;

	/** Stop Bytes */
	private static final byte CLA_STOP = (byte) 0xB6;
	private static final byte STOP_CAR = (byte) 0x01;
	private static final byte SET_FINAL_MILEAGE = (byte) 0x02;
	
	/** Keys Bytes */
	private static final byte CLA_KEYS = (byte) 0xB7;
	private static final byte KEYS_START = (byte) 0x01;
	private static final byte GET_PUBLIC_KEY_MODULUS = (byte) 0x02;
	private static final byte GET_PUBLIC_KEY_EXPONENT = (byte) 0x03;
	
	private static final short BLOCKSIZE = (short) 128;
	private static final short SCIDSIZE = (short) 2;
	private static final short NONCESIZE = (short) 6;
	
	/** Exceptions */
	private static final short NONCE_FAILURE = (short) 13000;
	private static final short SIGNATURE_FAILURE = (short) 13001;
	
	// Temporary buffer in RAM.
	byte[] tmp;

	// The applet state (INIT or ISSUED).
	byte state;
	
	// Is used to send a unique nonce, increment before use!
	byte[] nonce;
	
	// Cipher for encryption and decryption.
	Cipher cipher;

	// privkey_sc.
	RSAPrivateKey privKeySC;
	
	// pubkey_sc.
	RSAPublicKey pubKeySC;

	// pubkey_rt.
	RSAPublicKey pubKeyRT;
	
	// pubkey_ct.
	RSAPublicKey pubKeyCT;
	
	// sc_id.
	private short cardId;
	
	// {|sc_id, pubkey_sc|}privkey_rt.
	private byte[] signatureRT;
	
	// Car data, start mileage and final mileage
	private byte[] car_data;
	private byte[] start_mileage;
	private byte[] final_mileage;
	
	// boolean which indicates whether the car is started
	private boolean started;
	
	// Boolean which indicates whether the car has been started atleast once
	private boolean has_been_started;
	

	public static void install(byte[] bArray, short bOffset, byte bLength) throws SystemException {
		new RentalCarApplet();
	}

	public RentalCarApplet() {
		tmp = JCSystem.makeTransientByteArray((short) 256, JCSystem.CLEAR_ON_RESET);
		cipher = Cipher.getInstance(Cipher.ALG_RSA_PKCS1, false);
		
		// Create instances of keys.
		privKeySC = (RSAPrivateKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PRIVATE, KeyBuilder.LENGTH_RSA_1024, false);
		pubKeySC = (RSAPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PUBLIC, KeyBuilder.LENGTH_RSA_1024, false);
		pubKeyRT = (RSAPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PUBLIC, KeyBuilder.LENGTH_RSA_1024, false);
		pubKeyCT = (RSAPublicKey) KeyBuilder.buildKey(KeyBuilder.TYPE_RSA_PUBLIC, KeyBuilder.LENGTH_RSA_1024, false);
		
		// Initialize signature.
		signatureRT = new byte[128];
		
		// Initialize car data, start mileage and final mileage
		car_data = new byte[128];
		start_mileage = new byte[128];
		final_mileage = new byte[128];
		
		// Initialize state.
		state = STATE_INIT;
		
		// create nonce
		nonce = new byte[NONCESIZE];
		
		started = false;
		has_been_started = false;
		
		// Register this applet instance with the JCRE.
		register();
	}

	public void process(APDU apdu) {
		byte[] buf = apdu.getBuffer();
		byte ins = buf[OFFSET_INS];
		byte cla = buf[OFFSET_CLA];

		if (selectingApplet()) {
			return;
		}
		
		switch (state) {
			case STATE_INIT:
				issue(apdu, ins);
				break;
			case STATE_ISSUED:
				switch (cla) {
					case CLA_ISSUE:
						issue(apdu, ins);
						break;
					case CLA_INIT:
						init(apdu, ins);
						break;
					case CLA_READ:
						read(apdu, ins);
						break;
					case CLA_RESET:
						reset(apdu, ins);
						break;
					case CLA_START:
						start(apdu, ins);
						break;
					case CLA_STOP:
						stop(apdu, ins);
						break;
					case CLA_KEYS:
						keys(apdu, ins);
						break;
					default:
						ISOException.throwIt(SW_INS_NOT_SUPPORTED);
					}
				break;
			default:
				ISOException.throwIt(SW_CONDITIONS_NOT_SATISFIED);
		}
	}

	/**
	 * Copies <code>length</code> bytes of data (starting at
	 * <code>OFFSET_CDATA</code>) from <code>apdu</code> to <code>dest</code>
	 * (starting at <code>offset</code>).
	 * 
	 * This method will set <code>apdu</code> to incoming.
	 * 
	 * @param apdu
	 *            the APDU.
	 * @param dest
	 *            destination byte array.
	 * @param offset
	 *            offset into the destination byte array.
	 * @param length
	 *            number of bytes to copy.
	 */
	private void readBuffer(APDU apdu, byte[] dest, short offset, short length) {
		byte[] buf = apdu.getBuffer();
		short readCount = apdu.setIncomingAndReceive();
		short i = 0;
		Util.arrayCopy(buf, OFFSET_CDATA, dest, offset, readCount);
		while ((short) (i + readCount) < length) {
			i += readCount;
			offset += readCount;
			readCount = (short) apdu.receiveBytes(OFFSET_CDATA);
			Util.arrayCopy(buf, OFFSET_CDATA, dest, offset, readCount);
		}
	}

	private void issue(APDU apdu, byte ins) {
		
		byte[] buf = apdu.getBuffer();		
		short lc = (short) (buf[OFFSET_LC] & 0x00FF);
		readBuffer(apdu, tmp, (short)0, lc);
		
		switch (ins) {
			case SET_PUBLIC_KEY_SIGNATURE:
				// Store the signature of the RT.
				Util.arrayCopy(tmp, (short) 0, signatureRT, (short) 0, lc);
				
				// Send signature as a response for debugging info.
				apdu.setOutgoing();				
				Util.arrayCopy(signatureRT, (short) 0, buf, (short) 0, (short) 128);				
				apdu.setOutgoingLength((short) 128);				
				apdu.sendBytes((short) 0, (short) 128);
				
				break;
			case SET_PUBLIC_KEY_MODULUS_SC:
				// Store the modulus of the public key of the SC.
                pubKeySC.setModulus(tmp, (short) 0, lc);
                
                // Response for debugging.
                apdu.setOutgoing();				
				pubKeySC.getModulus(buf, (short) 0);		
				apdu.setOutgoingLength(lc);				
				apdu.sendBytes((short) 0, lc);
				
				break;
			case SET_PUBLIC_KEY_EXPONENT_SC:
				// Store the exponent of the public key of the SC.
                pubKeySC.setExponent(tmp, (short) 0, lc);
                
                // Response for debugging.
                apdu.setOutgoing();				
				pubKeySC.getExponent(buf, (short) 0);		
				apdu.setOutgoingLength(lc);				
				apdu.sendBytes((short) 0, lc);
				
				break;
			case SET_SC_ID:
				// Store the ID of the SC.
				cardId = Util.getShort(tmp, (short) 0);
				
				// Send cardId as a response for debugging info.
				buf[0] = (byte) ((cardId >> 8) & 0xff);
				buf[1] = (byte) (cardId & 0xff);
				apdu.setOutgoingAndSend((short) 0, (short) 2);
				
				break;
			case SET_START_NONCE:
				Util.arrayCopy(tmp, (short) 0, nonce, (short) 0, NONCESIZE);
			case SET_PRVATE_KEY_MODULUS_SC:
				// Store the modulus of the private key of the SC.
                privKeySC.setModulus(tmp, (short) 0, lc);
				break;
			case SET_PRIVATE_KEY_EXPONENT_SC:
				// Store the exponent of the private key of the SC.
                privKeySC.setExponent(tmp, (short) 0, lc);
			case SET_PUBLIC_KEY_MODULUS_RT:
				// Store the modulus of the public key of the RT.
                pubKeyRT.setModulus(tmp, (short) 0, lc);
				break;
			case SET_PUBLIC_KEY_EXPONENT_RT:
				// Store the modulus of the public key of the RT.
                pubKeyRT.setExponent(tmp, (short) 0, lc);                
                // Set state to "Issued".
                state = STATE_ISSUED;                
				break;
			default:
				ISOException.throwIt(SW_INS_NOT_SUPPORTED);
		}
	}

	private void init(APDU apdu, byte ins) {
		
		byte[] buf = apdu.getBuffer();
		short lc = (short) (buf[OFFSET_LC] & 0x00FF);
		readBuffer(apdu, tmp, (short)0, lc);
		
		switch (ins) {
		case INIT_START:
			/**Util.arrayCopy(buf, (short) 0, tmp, (short) 0, lc);
			apdu.setOutgoing();
			cipher.init(privKeySC,Cipher.MODE_DECRYPT);
            cipher.doFinal(tmp,(short)0,(short)2,buf,(short)0);
            apdu.sendBytes((short)0,NONCESIZE);
			break;**/
			
			/*Util.arrayCopy(buf, (short) 0, tmp, (short) 0, lc);
			apdu.setOutgoing();
			cipher.init(privKeySC,Cipher.MODE_DECRYPT);
            cipher.doFinal(tmp,(short)0,(short)2,buf,(short)0);
            apdu.sendBytes((short)0,NONCESIZE);*/
            
            
            byte[] returnValue = new byte[2];
            Util.arrayCopy(tmp, (short) 0, returnValue, (short) 0, lc);
            apdu.setOutgoing();				
			Util.arrayCopy(returnValue, (short) 0, buf, (short) 0, (short) 2);				
			apdu.setOutgoingLength(lc);				
			apdu.sendBytes((short) 0, (short) 2);
			
			break;

		case INIT_AUTHENTICATED:
			incrementNonce();
			apdu.setOutgoing();
			Util.arrayCopy(nonce, (byte)0, tmp, (byte)0, NONCESIZE);
			cipher.init(pubKeyRT,Cipher.MODE_ENCRYPT);
			cipher.doFinal(tmp,(short)0,NONCESIZE,buf,(short)0);
			apdu.sendBytes((short)0,NONCESIZE);
			break;
		case INIT_SECOND_NONCE:
			byte[] received_nonce = new byte[NONCESIZE];
			Util.arrayCopy(buf, (short)0, received_nonce, (short)0, NONCESIZE);
			for (byte i = 0; i < NONCESIZE; i++){
				if (received_nonce[i] != nonce[i]){
					ISOException.throwIt(NONCE_FAILURE);
				}
			}
			break;
		case INIT_SET_CAR_KEY_MODULUS:
			//Store the length of the modulus as the first 2 bytes in the tmp array
			Util.setShort(tmp,(short) 0 , lc);
			//Store the modulus in tmp at position 4
			Util.arrayCopy(buf, (short) 0, tmp, (short) 4, lc);
			break;
		case INIT_SET_CAR_KEY_EXPONENT:
			//Get the length of the modulus as the first 2 bytes in the tmp array
			short length = Util.getShort(tmp, (short)0);
			//Set the length of the exponent as the first 2 bytes after the modulus
			Util.setShort(tmp,(short) 2, lc);
			//Store the exponent in tmp
			Util.arrayCopy(buf, (short) 0, tmp, (short)(4 + length), lc);			
		case INIT_CHECK_CAR_KEY_SIGNATURE:
			short modulus_length = Util.getShort(tmp, (short)0);
			short exponent_length = Util.getShort(tmp, (short)2);
			//Check signature
			Signature instance = Signature.getInstance(Signature.ALG_RSA_SHA_PKCS1, false);
			boolean verified = instance.verify(tmp, (short)4, (short)(modulus_length + exponent_length), buf, (short)0, lc);
			if (verified) {
				pubKeyCT.setModulus(tmp, (short)4, modulus_length);
				pubKeyCT.setExponent(tmp, (short)(4 + modulus_length), exponent_length);
			} else {
				ISOException.throwIt(SIGNATURE_FAILURE);
			}
			
		case INIT_SET_SIGNED_ENCRYPTED_CAR_DATA:
			// decrypt the car data and store it
			Util.arrayCopy(buf, (short)0, car_data, (short)0, BLOCKSIZE);
			break;
		default:
			ISOException.throwIt(SW_INS_NOT_SUPPORTED);
		}
	}

	
	private void read(APDU apdu, byte ins) {
		
		byte[] buf = apdu.getBuffer();
		short lc = (short) (buf[OFFSET_LC] & 0x00FF);
		readBuffer(apdu, tmp, (short)0, lc);
		
		switch (ins) {
			case READ_MILEAGE_SIGNED_NONCE:
				// decrypt nonce with private_key_sc and send it
				cipher.init(privKeySC, Cipher.MODE_DECRYPT);
				cipher.doFinal(buf, (short)0, NONCESIZE, tmp, (short)0);
				apdu.setOutgoing();				
				Util.arrayCopy(tmp, (short) 0, buf, (short) 0, NONCESIZE);
				apdu.setOutgoingLength((short) NONCESIZE);				
				apdu.sendBytes((short) 0, (short) NONCESIZE);
				break;
			case READ_MILEAGE_START_MILEAGE:
				// send start mileage
				apdu.setOutgoing();
				Util.arrayCopy(start_mileage, (short) 0, buf, (short) 0, BLOCKSIZE);
				apdu.setOutgoingLength((short) BLOCKSIZE);				
				apdu.sendBytes((short) 0, (short) BLOCKSIZE);
				break;
			case READ_MILEAGE_FINAL_MILEAGE:
				// send final mileage
				apdu.setOutgoing();
				Util.arrayCopy(final_mileage, (short) 0, buf, (short) 0, BLOCKSIZE);
				apdu.setOutgoingLength((short) BLOCKSIZE);				
				apdu.sendBytes((short) 0, (short) BLOCKSIZE);
				break;
			default:
				ISOException.throwIt(SW_INS_NOT_SUPPORTED);
		}
	}
	//TODO: we vertrouwen nu op de garbage collector om de velden te resetten, misschien geen goed idee.
	private void reset(APDU apdu, byte ins) {
		switch (ins) {
			case RESET_CARD:
				// started = false
				// final_mileage = 0
				// start_mileage = 0
				// public_key_ct = 0
				// car_data = 0
				started = false;
				has_been_started = false;
				final_mileage = new byte[128];
				start_mileage = new byte[128];
				pubKeyCT.clearKey();
				car_data = new byte[128];
				break;
			default:
				ISOException.throwIt(SW_INS_NOT_SUPPORTED);
		}
	}

	private void start(APDU apdu, byte ins) {
		
		byte[] buf = apdu.getBuffer();	
		short lc = (short) (buf[OFFSET_LC] & 0x00FF);
		readBuffer(apdu, tmp, (short)0, lc);
		
		switch (ins) {
			case SET_START_MILEAGE:
				// started = true
				started = true;
				// if start_mileage == 0, start_mileage = received_start_mileage
				if (!has_been_started){
					Util.arrayCopy(buf, (short)0, start_mileage, (short)0, BLOCKSIZE);
					has_been_started = true;
				}
				// send car data			
				apdu.setOutgoing();				
				Util.arrayCopy(car_data, (short) 0, buf, (short) 0, BLOCKSIZE);
				apdu.setOutgoingLength((short) BLOCKSIZE);				
				apdu.sendBytes((short) 0, (short) BLOCKSIZE);
				
				break;
			default:
				ISOException.throwIt(SW_INS_NOT_SUPPORTED);
		}
	}
	//TODO: nonce wordt niet gechecked atm, moet dus nog gebeuren
	private void stop(APDU apdu, byte ins) {
		
		byte[] buf = apdu.getBuffer();
		short lc = (short) (buf[OFFSET_LC] & 0x00FF);
		readBuffer(apdu, tmp, (short)0, lc);
		
		switch (ins) {
			case STOP_CAR:
				// increment nonce
				incrementNonce();
				// send nonce
				apdu.setOutgoing();				
				Util.arrayCopy(nonce, (short)0, buf, (short)0, NONCESIZE);
				apdu.setOutgoingLength((short) NONCESIZE);				
				apdu.sendBytes((short) 0, (short) NONCESIZE);
				break;
			case SET_FINAL_MILEAGE:
				// decrypt(public_key_sc {nonce + final_mileage})
				// if received_nonce == stored_nonce continue, else exception
				// store final_mileage
				started = false;
				Util.arrayCopy(buf, (short)0, final_mileage, (short)0, BLOCKSIZE);
				
				break;
			default:
				ISOException.throwIt(SW_INS_NOT_SUPPORTED);
		}
	}
	
	private void keys(APDU apdu, byte ins){
		
		byte[] buf = apdu.getBuffer();
		//short lc = (short) (buf[OFFSET_LC] & 0x00FF);
		//readBuffer(apdu, tmp, (short)0, lc);
		
		switch (ins) {
			case KEYS_START:
				// Send SC_ID + signature
				buf[0] = (byte) ((cardId >> 8) & 0xff);
				buf[1] = (byte) (cardId & 0xff);
				Util.arrayCopy(signatureRT, (short) 0, buf, (short) 2, BLOCKSIZE);				
				apdu.setOutgoingAndSend((short) 0, (short) (SCIDSIZE + BLOCKSIZE));
				break;
			case GET_PUBLIC_KEY_MODULUS:
				// Send pubkey_sc modulus.
				pubKeySC.getModulus(buf, (short) 0);				
				apdu.setOutgoingAndSend((short) 0, BLOCKSIZE);			
				/*
				pubKeySC.getModulus(buf, OFFSET_CDATA);
				apdu.setOutgoing();
				apdu.setOutgoingLength(BLOCKSIZE);
				apdu.sendBytesLong(buf, OFFSET_CDATA, BLOCKSIZE);*/
				break;
			case GET_PUBLIC_KEY_EXPONENT:
				// Send pubkey_sc exponent
				pubKeySC.getExponent(buf, (short) 0);
				apdu.setOutgoingAndSend((short) 0, (short) 3);
				/*pubKeySC.getExponent(buf, OFFSET_CDATA);
				apdu.setOutgoing();
				apdu.setOutgoingLength(BLOCKSIZE);
				apdu.sendBytesLong(buf, OFFSET_CDATA, BLOCKSIZE);*/
				break;
			default:
				ISOException.throwIt(SW_INS_NOT_SUPPORTED);
		}
	}
	
	private void incrementNonce(){
		nonce[0]++;
		for (byte i = 0; i < NONCESIZE - 1; i++){
			if (nonce[i] == (byte)0){
				nonce[i + 1]++;
			}
		}
		if (nonce[NONCESIZE] == (byte)0){
			nonce[0]++;
		}
	}
}
