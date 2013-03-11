package org.aprsdroid.app

import _root_.android.app.Service
import _root_.android.content.Context
import _root_.android.location.{Location, LocationManager}
import _root_.android.util.Log
import _root_.java.io.{BufferedReader, File, InputStream, InputStreamReader, OutputStream, OutputStreamWriter, PrintWriter}
import _root_.java.net.{InetAddress, Socket}
import _root_.java.security.KeyStore
import _root_.java.security.cert.X509Certificate
import _root_.javax.net.ssl.{KeyManagerFactory, SSLContext, SSLSocket, X509TrustManager}
import _root_.net.ab0oo.aprs.parser.APRSPacket

class TcpUploader(service : AprsService, prefs : PrefsWrapper) extends AprsIsUploader(prefs) {
	val TAG = "APRSdroid.TcpUploader"
	val hostname = prefs.getString("tcp.server", "euro.aprs2.net")
	val so_timeout = prefs.getStringInt("tcp.sotimeout", 120)
	val RECONNECT = 30
	var conn : TcpSocketThread = null

	def start() = {
		if (conn == null)
			createConnection()
		false
	}

	def setupFilter() : String = {
		val filterdist = prefs.getStringInt("tcp.filterdist", 50)
		val userfilter = prefs.getString("tcp.filter", "")
		val lastloc = AprsPacket.formatRangeFilter(
			service.getSystemService(Context.LOCATION_SERVICE).asInstanceOf[LocationManager]
				.getLastKnownLocation(LocationManager.GPS_PROVIDER), filterdist)
		if (filterdist == 0) return " filter %s %s".format(userfilter, lastloc)
				else return " filter m/%d %s %s".format(filterdist, userfilter, lastloc)
	}

	def createConnection() {
		val (host, port) = AprsPacket.parseHostPort(hostname, 14580)
		Log.d(TAG, "TcpUploader.createConnection: " + host + ":" + port)
		conn = new TcpSocketThread(host, port)
		conn.start()
	}

	def createTncProto(is : InputStream, os : OutputStream) : TncProto = {
		Log.d(TAG, login + setupFilter())
		new AprsIsProto(is, os, login + setupFilter())
	}

	def update(packet : APRSPacket) : String = {
		Log.d(TAG, "TcpUploader.update: " + packet)
		conn.update(packet)
	}

	def stop() {
		conn.synchronized {
			conn.running = false
		}
		conn.shutdown()
		conn.interrupt()
		conn.join(50)
	}

	class TcpSocketThread(host : String, port : Int)
			extends Thread("APRSdroid TCP connection") {
		val TAG = "APRSdroid.TcpSocketThread"
		var running = true
		var socket : Socket = null
		var tnc : TncProto = null

		val KEYSTORE_DIR = "keystore"
		val KEYSTORE_FILE = "keys.bks"
		val KEYSTORE_PASS = "APRS-IS".toCharArray()

		def init_ssl_socket(host : String, port : Int) : Socket = {
			val dir = service.getApplicationContext().getDir(KEYSTORE_DIR, Context.MODE_PRIVATE)
			val keyStoreFile = new File(dir + File.separator + prefs.getCallsign() + ".bks")

			val ks = KeyStore.getInstance("BKS")
			val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())

			try {
				ks.load(new java.io.FileInputStream(keyStoreFile), KEYSTORE_PASS)
				kmf.init(ks, KEYSTORE_PASS)
				service.postAddPost(StorageDatabase.Post.TYPE_INFO, R.string.post_info, "Loaded Client certs: " + ks.size())
			} catch {
				case e : Exception =>
					e.printStackTrace()
					service.postAddPost(StorageDatabase.Post.TYPE_INFO, R.string.post_info, e.toString())
					return null
			}

			val sc = SSLContext.getInstance("TLS")
			sc.init(kmf.getKeyManagers(), Array(new NaiveTrustManager()), null)
			val socket = sc.getSocketFactory().createSocket(host, port).asInstanceOf[SSLSocket]
			socket
		}

		def init_socket() {
			Log.d(TAG, "init_socket()")
			service.postAddPost(StorageDatabase.Post.TYPE_INFO, R.string.post_info,
				service.getString(R.string.post_connecting, host, port.asInstanceOf[AnyRef]))
			this.synchronized {
				if (!running) {
					Log.d(TAG, "init_socket() aborted")
					return;
				}
				socket = init_ssl_socket(host, port)
				if (socket == null)
					socket = new Socket(host, port)
				socket.setKeepAlive(true)
				socket.setSoTimeout(so_timeout*1000)
				tnc = createTncProto(socket.getInputStream(), socket.getOutputStream())
			}
			Log.d(TAG, "init_socket() done")
		}

		override def run() {
			import StorageDatabase.Post._
			var need_reconnect = false
			Log.d(TAG, "TcpSocketThread.run()")
			try {
				init_socket()
				service.postPosterStarted()
			} catch {
				case e : Exception => service.postAbort(e.toString()); running = false
			}
			while (running) {
				try {
					if (need_reconnect) {
						need_reconnect = false
						shutdown()
						init_socket()
					}
					Log.d(TAG, "waiting for data...")
					var line : String = null
					while (running && { line = tnc.readPacket(); line != null }) {
						Log.d(TAG, "recv: " + line)
						if (line(0) != '#')
							service.postSubmit(line)
						else
							service.postAddPost(TYPE_INFO, R.string.post_info, line)
					}
					if (running && (line == null || !socket.isConnected())) {
						Log.d(TAG, "reconnecting in 30s")
						service.postAddPost(TYPE_INFO, R.string.post_info,
							service.getString(R.string.post_reconnect, RECONNECT.asInstanceOf[AnyRef]))
						shutdown()
						Thread.sleep(RECONNECT*1000)
						init_socket()
					}
				} catch {
					case se : java.net.SocketTimeoutException =>
						Log.i(TAG, "restarting due to timeout")
						need_reconnect = true
					case e : Exception => Log.d(TAG, "Exception " + e)
				}
			}
			Log.d(TAG, "TcpSocketThread.terminate()")
		}

		def update(packet : APRSPacket) : String = {
			if (socket != null && socket.isConnected()) {
				tnc.writePacket(packet)
				"TCP OK"
			} else "TCP disconnected"
		}

		def catchLog(tag : String, fun : ()=>Unit) {
			Log.d(TAG, "catchLog(" + tag + ")")
			try {
				fun()
			} catch {
			case e : Exception => Log.d(TAG, tag + " execption: " + e)
			}
		}

		def shutdown() {
			Log.d(TAG, "shutdown()")
			this.synchronized {
				catchLog("shutdownInput", socket.shutdownInput)
				catchLog("shutdownOutput", socket.shutdownOutput)
				catchLog("socket.close", socket.close)
				socket = null
			}
		}
	}

	class NaiveTrustManager extends X509TrustManager {

		override def checkClientTrusted(cert: Array[X509Certificate], authType: String) {
		}

		override def checkServerTrusted(cert: Array[X509Certificate], authType: String) {
		}

		override def getAcceptedIssuers = null
	}
}
