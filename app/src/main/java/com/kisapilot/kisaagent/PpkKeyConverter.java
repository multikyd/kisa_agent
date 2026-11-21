package com.kisapilot.kisaagent;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/** PuTTY PPK v2/v3 -> unencrypted OpenSSH, after MAC validation on the phone. */
final class PpkKeyConverter {
    static byte[] convert(byte[] input, char[] passphrase) throws Exception {
        if(input.length>16384)throw new IOException("KEY_TOO_LARGE");
        String text=new String(input,StandardCharsets.ISO_8859_1);
        if(!text.startsWith("PuTTY-User-Key-File-")) {
            if(!text.startsWith("-----BEGIN ")||text.contains("ENCRYPTED"))throw new IOException("UNSUPPORTED_KEY");
            return input.clone();
        }
        Map<String,String> fields=new LinkedHashMap<>();
        String[] lines=text.split("\\r\\n|\\r|\\n");
        for(int i=0;i<lines.length;i++) {
            String line=lines[i]; if(line.isEmpty())continue;
            int at=line.indexOf(": ");if(at<0)throw new IOException("INVALID_PPK");
            String key=line.substring(0,at),value=line.substring(at+2);
            if(fields.containsKey(key))throw new IOException("INVALID_PPK");
            if(key.equals("Public-Lines")||key.equals("Private-Lines")) {
                int count=Integer.parseInt(value);if(count<1||count>256||i+count>=lines.length)throw new IOException("INVALID_PPK");
                StringBuilder blob=new StringBuilder();while(count-->0)blob.append(lines[++i]);value=blob.toString();
            }
            fields.put(key,value);
        }
        int version=fields.containsKey("PuTTY-User-Key-File-3")?3:fields.containsKey("PuTTY-User-Key-File-2")?2:0;
        if(version==0)throw new IOException("UNSUPPORTED_PPK_VERSION");
        String algorithm=required(fields,"PuTTY-User-Key-File-"+version),encryption=required(fields,"Encryption");
        if(!encryption.equals("none")&&!encryption.equals("aes256-cbc"))throw new IOException("UNSUPPORTED_PPK_CIPHER");
        byte[] pub=Base64.getDecoder().decode(required(fields,"Public-Lines"));
        byte[] priv=Base64.getDecoder().decode(required(fields,"Private-Lines"));
        byte[] password=encryption.equals("none")?new byte[0]:new String(passphrase).getBytes(StandardCharsets.UTF_8);
        byte[] material=new byte[0],macKey=new byte[0],iv=new byte[16],aesKey=new byte[32];
        try {
            if(version==2) {
                macKey=MessageDigest.getInstance("SHA-1").digest(join("putty-private-key-file-mac-key".getBytes(StandardCharsets.US_ASCII),password));
                if(!encryption.equals("none")) {
                    material=join(MessageDigest.getInstance("SHA-1").digest(join(new byte[]{0,0,0,0},password)),
                                  MessageDigest.getInstance("SHA-1").digest(join(new byte[]{0,0,0,1},password)));
                    System.arraycopy(material,0,aesKey,0,32);
                }
            } else if(!encryption.equals("none")) {
                int memory=bounded(fields,"Argon2-Memory",8,262144),passes=bounded(fields,"Argon2-Passes",1,64),parallel=bounded(fields,"Argon2-Parallelism",1,8);
                String kind=required(fields,"Key-Derivation");
                int type=kind.equals("Argon2id")?Argon2Parameters.ARGON2_id:kind.equals("Argon2i")?Argon2Parameters.ARGON2_i:kind.equals("Argon2d")?Argon2Parameters.ARGON2_d:-1;
                if(type<0)throw new IOException("UNSUPPORTED_PPK_KDF");
                byte[] salt=hex(required(fields,"Argon2-Salt"));if(salt.length<8||salt.length>64)throw new IOException("INVALID_PPK");
                Argon2BytesGenerator kdf=new Argon2BytesGenerator();
                kdf.init(new Argon2Parameters.Builder(type).withVersion(Argon2Parameters.ARGON2_VERSION_13)
                        .withMemoryAsKB(memory).withIterations(passes).withParallelism(parallel).withSalt(salt).build());
                material=new byte[80];kdf.generateBytes(password,material);
                aesKey=Arrays.copyOfRange(material,0,32);iv=Arrays.copyOfRange(material,32,48);macKey=Arrays.copyOfRange(material,48,80);
            }
            if(!encryption.equals("none")) {
                Cipher cipher=Cipher.getInstance("AES/CBC/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(aesKey,"AES"),new IvParameterSpec(iv));
                byte[] decoded=cipher.doFinal(priv);Arrays.fill(priv,(byte)0);priv=decoded;
            }
            byte[] preimage=join(string(algorithm),string(encryption),string(required(fields,"Comment")),string(pub),string(priv));
            Mac mac=Mac.getInstance(version==2?"HmacSHA1":"HmacSHA256");
            // HMAC empty key and a zero byte have the same zero-padded block.
            mac.init(new SecretKeySpec(macKey.length==0?new byte[1]:macKey,mac.getAlgorithm()));
            boolean valid=MessageDigest.isEqual(mac.doFinal(preimage),hex(required(fields,"Private-MAC")));
            Arrays.fill(preimage,(byte)0);
            if(!valid)throw new IOException("PPK_PASSPHRASE_OR_MAC");
            return openssh(algorithm,pub,priv);
        } finally {
            for(byte[] bytes:new byte[][]{password,priv,material,macKey,iv,aesKey})Arrays.fill(bytes,(byte)0);
        }
    }
    private static byte[] openssh(String algorithm,byte[] pub,byte[] priv)throws Exception {
        DataInputStream p=new DataInputStream(new ByteArrayInputStream(pub)),q=new DataInputStream(new ByteArrayInputStream(priv));
        if(!new String(read(p),StandardCharsets.US_ASCII).equals(algorithm))throw new IOException("INVALID_PPK");
        ByteArrayOutputStream block=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(block);
        int check=new SecureRandom().nextInt();out.writeInt(check);out.writeInt(check);out.write(string(algorithm));
        if(algorithm.equals("ssh-rsa")) {
            byte[] e=read(p),n=read(p),d=read(q),primeP=read(q),primeQ=read(q),iqmp=read(q);
            for(byte[] v:new byte[][]{n,e,d,iqmp,primeP,primeQ})out.write(string(v));
        } else if(algorithm.startsWith("ecdsa-sha2-nistp")) {
            out.write(string(read(p)));out.write(string(read(p)));out.write(string(read(q)));
        } else if(algorithm.equals("ssh-ed25519")) {
            byte[] publicPoint=read(p),number=read(q);if(publicPoint.length!=32||number.length>33)throw new IOException("INVALID_PPK");
            if(number.length==33&&number[0]!=0)throw new IOException("INVALID_PPK");
            byte[] seed=new byte[32];int n=Math.min(32,number.length);System.arraycopy(number,number.length-n,seed,32-n,n);
            out.write(string(publicPoint));out.write(string(join(seed,publicPoint)));Arrays.fill(seed,(byte)0);
        } else throw new IOException("UNSUPPORTED_PPK_ALGORITHM");
        out.write(string("KisaAgent imported PPK"));
        int pad=1;while(block.size()%8!=0)out.writeByte(pad++);
        byte[] result=join("openssh-key-v1\0".getBytes(StandardCharsets.US_ASCII),string("none"),string("none"),string(new byte[0]),new byte[]{0,0,0,1},string(pub),string(block.toByteArray()));
        String encoded=Base64.getMimeEncoder(70,new byte[]{'\n'}).encodeToString(result);Arrays.fill(result,(byte)0);
        return ("-----BEGIN OPENSSH PRIVATE KEY-----\n"+encoded+"\n-----END OPENSSH PRIVATE KEY-----\n").getBytes(StandardCharsets.US_ASCII);
    }
    static byte[] string(String value)throws IOException{return string(value.getBytes(StandardCharsets.ISO_8859_1));}
    static byte[] string(byte[] value)throws IOException {ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);d.writeInt(value.length);d.write(value);return b.toByteArray();}
    static byte[] join(byte[]...parts)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();for(byte[] p:parts)b.write(p);return b.toByteArray();}
    private static byte[] read(DataInputStream in)throws IOException{int n=in.readInt();if(n<0||n>16384||n>in.available())throw new IOException("INVALID_PPK");byte[] b=new byte[n];in.readFully(b);return b;}
    private static String required(Map<String,String> f,String key)throws IOException{if(!f.containsKey(key))throw new IOException("INVALID_PPK");return f.get(key);}
    private static int bounded(Map<String,String> f,String key,int lo,int hi)throws Exception{int v=Integer.parseInt(required(f,key));if(v<lo||v>hi)throw new IOException("PPK_KDF_LIMIT");return v;}
    static byte[] hex(String value)throws IOException{if(!value.matches("[a-fA-F0-9]+")||value.length()%2!=0)throw new IOException("INVALID_PPK");byte[] b=new byte[value.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);return b;}
}
