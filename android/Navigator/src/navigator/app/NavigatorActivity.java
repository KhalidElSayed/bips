package navigator.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.NoSuchElementException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import com.google.android.maps.GeoPoint;
import com.group057.IRemoteService;
import com.group057.IRemoteServiceCallback;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.provider.Settings;

import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;

import android.widget.Button;
import android.widget.EditText;


public class NavigatorActivity extends Activity{
	

	private Button setDestination;
	private Button getDirections;
	private EditText directions;
	private LinkedList<Step> steps;
	private LocationManager locationManager;
	private LocationProvider locationProvider;
    //private static final int TWO_MINUTES = 1000 * 60 * 2;
    //private static final int THIRTY_SEC = 1000* 30;
    private static final int FIVE_SEC = 1000* 5;
	private boolean running = false;
	private Location currentBestLocation = null;
	private static final int DESINATION_SELECTION=0;
    private static final int REQUEST_ENABLE_BT = 3;
	private GeoPoint to = null;
	private Thread turnByturn;
	private Thread retrieveDirections;
	static boolean  reCalcDist = true;
	static boolean  sentMessage = false;
	private BluetoothAdapter mBluetoothAdapter = null;
	private boolean simulation = false;
	private MockLocationProvider mLP;
	
	/** Messenger for communicating with service. */
	Messenger mService = null;
	IRemoteService mIRemoteService = null;
	/** Flag indicating whether we have called bind on the service. */
	boolean mIsBound;
	/** Some text view we are using to show state information. */
	//static TextView mCallbackText;
	
	private String directionsString = "";
	
	
	
	private static final byte[] right = { 0, 0, 0, 0, 0, 0, (byte) 0x20,
		(byte) 0x20, (byte) 0x20, (byte) 0xf8, (byte) 0x70, (byte) 0x20, 0,
		0, 0, 0, 0, 0, 0, 0 };
	private static final byte[] left = { 0, 0, 0, 0, 0, 0, (byte) 0x20,
		(byte) 0x70, (byte) 0xf8, (byte) 0x20, (byte) 0x20, (byte) 0x20, 0,
		0, 0, 0, 0, 0, 0, 0 };
	
	private static final String NAV = "Navigator";
	
	/** Called when the activity is first created. */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
        setDestination = (Button)findViewById(R.id.swapMapView);
        setDestination.setOnClickListener(swapMapView);
        getDirections = (Button)findViewById(R.id.getDirections);
        getDirections.setOnClickListener(getDirectionsListener);
        directions = (EditText)findViewById(R.id.directions);
        directions.setKeyListener(null);
        locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);
        locationProvider = locationManager.getProvider(LocationManager.GPS_PROVIDER);
        if(!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        	Intent settingsIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        	startActivity(settingsIntent);
        }
        
        Button simulation = (Button)findViewById(R.id.simulation);
        simulation.setOnClickListener(startSimulation);
        
        // If BT is not on, request that it be enabled.
        // setupChat() will then be called during onActivityResult
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);
        // Otherwise, setup the chat session
        } else {
            // Bind Bips
            doBindService();
        }

   

        // Define a listener that responds to location updates
        LocationListener locationListener = new LocationListener() {
            public void onLocationChanged(Location location) {
            	if (isBetterLocation(location, currentBestLocation)) {
            		currentBestLocation = location;
            		reCalcDist = true;
            		Log.d(NAV, "Test1");
            	}
            	              
            }

            public void onStatusChanged(String provider, int status, Bundle extras) {}

            public void onProviderEnabled(String provider) {}

            public void onProviderDisabled(String provider) {}
          };

          
          
        locationManager.addTestProvider(LocationManager.GPS_PROVIDER, false, false, false, false, false, true, true, 0, 0);
        // Register the listener with the Location Manager to receive location updates
        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, locationListener);
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, locationListener);
        
    }
    
    public void onDestory() {
    	turnByturn.stop();
    	doUnbindService();
    }


    /** Determines whether one Location reading is better than the current Location fix
      * @param location  The new Location that you want to evaluate
      * @param currentBestLocation  The current Location fix, to which you want to compare the new one
      */
    protected boolean isBetterLocation(Location location, Location currentBestLocation) {
        if (currentBestLocation == null) {
            // A new location is always better than no location
            return true;
        }

        // Check whether the new location fix is newer or older
        long timeDelta = location.getTime() - currentBestLocation.getTime();
        boolean isSignificantlyNewer = timeDelta > FIVE_SEC;
        boolean isSignificantlyOlder = timeDelta < -FIVE_SEC;
        boolean isNewer = timeDelta > 0;

        // If it's been more than two minutes since the current location, use the new location
        // because the user has likely moved
        if (isSignificantlyNewer) {
            return true;
        // If the new location is more than two minutes older, it must be worse
        } else if (isSignificantlyOlder) {
            return false;
        }

        // Check whether the new location fix is more or less accurate
        int accuracyDelta = (int) (location.getAccuracy() - currentBestLocation.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isMoreAccurate = accuracyDelta < 0; //For demo purposes = case is included
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;

        // Check if the old and new location are from the same provider
        boolean isFromSameProvider = isSameProvider(location.getProvider(),
                currentBestLocation.getProvider());

        // Determine location quality using a combination of timeliness and accuracy
        if (isMoreAccurate) {
            return true;
        } else if (isNewer && !isLessAccurate) {
            return true;
        } else if (isNewer && !isSignificantlyLessAccurate && isFromSameProvider) {
            return true;
        }
        return false;
    }

    /** Checks whether two providers are the same */
    private boolean isSameProvider(String provider1, String provider2) {
        if (provider1 == null) {
          return provider2 == null;
        }
        return provider1.equals(provider2);
    }
    
    
    /**
     * 
     */
    private void startApplication() {
    	
    	Step s = steps.pop(); //First Step
    	Step upComingStep = steps.getFirst();
    	
    	boolean reCalcDest = true;
    	Location destination = new Location("Destination");
    	Location nextDest = new Location("nextDest");

    	float distance = -1;
    	float nextDist = -1;
    	
    	while(running) {
    
    		if( reCalcDest ) {
    			destination.setLongitude(s.getEnd().getLongitudeE6()/1E6);
    			destination.setLatitude(s.getEnd().getLatitudeE6()/1E6);
    			if(upComingStep != null) {
    				nextDest.setLatitude(upComingStep.getEnd().getLatitudeE6()/1E6);
    				nextDest.setLongitude(upComingStep.getEnd().getLongitudeE6()/1E6);	
    			}
    			reCalcDest = false;
    		}
    		
    		if ( reCalcDist ) {
    			float tmpdistance = currentBestLocation.distanceTo(destination);
    			float tmpnextDist = currentBestLocation.distanceTo(nextDest);
    			
    			boolean checkPointReached = false;
    			if(tmpdistance > distance && tmpnextDist < nextDist) {
    				checkPointReached = true;
    			}
    			distance = tmpdistance;
    			tmpnextDist = nextDist;
    			
    			reCalcDist = false;
    			Log.d(NAV, "Test2");
    			//Log.d(NAV, "Distance: " + distance);
    			//Log.d(NAV, "nextDist: " + nextDist);    		
	    		if( distance < 30 ) {
	    			//Send Information to BIPS//
	    			if (upComingStep != null) {
		    			try{
			    			switch(upComingStep.getCurrentArrow()) {
			    				case LEFT:
			    					mIRemoteService.imageRequestQueue(left, 5000
			    							, (byte) 0, getPackageName());
			    				break;
			    				case RIGHT:
			    					mIRemoteService.imageRequestQueue(right, 5000
			    							, (byte) 0, getPackageName());
			    				break;
			    			}
			    		} catch (RemoteException e) {
			    			// TODO Auto-generated catch block
			    			e.printStackTrace();
			    		}
	    				directionsString = upComingStep.toString(distance);
	    			} else {
	    				directionsString = "Final Destination Close - Within "+ distance +"m";
	    			}
	    			
	    			//mIRemoteService.imageRequestCancelCurrent(Process.myPid());
	    	    	runOnUiThread(new Runnable(){
	    				public void run() {
	    					updateDirections();
	    				}
	    	    	});
	    	    	
	    	    	destination.setLongitude(s.getEnd().getLongitudeE6()/1E6);
	    			destination.setLatitude(s.getEnd().getLatitudeE6()/1E6);
	    	    	
	    			if (distance < 8 || checkPointReached) {
		    			try {
		    				mIRemoteService.imageRequestCancelAll(getPackageName());
		    				s = steps.pop();
		    				if (steps.size() != 0) {
		    					upComingStep = steps.getFirst();
		    				} else {
		    					upComingStep = null;
		    				}
		    				//Recalculated everything.
		    				reCalcDest = true;
		    				reCalcDist = true;
		    				
	    				} catch (NoSuchElementException e) {
	    					//Steps List has been finished, user has reached destination.
	    					directionsString = "\n Arrived at destination";
	    					running = false;
	    				} catch (RemoteException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
	    			}
    			}
    		} /*else {
    			directionsString = "Continue On Current Road for the next " + distance +"m";
      	    	runOnUiThread(new Runnable(){
    				public void run() {
    					updateDirections();
    				}
    	    	});
    		}*/
    		
    		
    		
    	}
    	directionsString = "Arrived at the destination :D";
	    	runOnUiThread(new Runnable(){
			public void run() {
				updateDirections();
			}
    	});
    	
    }
    
    
    private void updateDirections() {
    	directions.setText(directionsString);
    }
    
    private OnClickListener swapMapView = new OnClickListener() {
    	public void onClick(View v) {
    		
            if (currentBestLocation == null) {
                return ;
            }
            
            Bundle bundle = new Bundle();
            bundle.putDouble("myLat", currentBestLocation.getLatitude());
            bundle.putDouble("myLong", currentBestLocation.getLongitude());
            
    	    //first create new intent to call ColorSelectorActivity 
    	    Intent request = new Intent(NavigatorActivity.this, DestinationActivity.class);
    	    request.putExtras(bundle);
    	    /*then start new ColorSelectorActivity and waiting for its result 
    	    here we use COLOR_SELECTOR unic code because we might have lot of 
    	    activity call from this activity for ignoring complict of that
    	    we use unic request code for each activity*/   
    	    startActivityForResult(request, DESINATION_SELECTION); 
    	}
    };
    
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    	switch(requestCode) {
    	case DESINATION_SELECTION:
    		if(resultCode == RESULT_OK){
    			Bundle b = data.getExtras();
    			to = new GeoPoint(b.getInt("destLat"),b.getInt("destLong"));
    		}
    	break;
        case REQUEST_ENABLE_BT:
            // When the request to enable Bluetooth returns
            if (resultCode == Activity.RESULT_OK) {
                // Bluetooth is now enabled, so set up a chat session
                doBindService();
            } 
        break;
    	}
    }
    
    
    private OnClickListener startSimulation = new OnClickListener() {
		public void onClick(View v) {
			try {

				List<String> data = new ArrayList<String>();
				InputStream is = getAssets().open("data.txt");
				BufferedReader reader = new BufferedReader(new InputStreamReader(is));
				String line = null;
				while ((line = reader.readLine()) != null) {

					data.add(line);
				}
				
				//43.47353	-80.53231
				currentBestLocation = new Location("");
				currentBestLocation.setLatitude(43.471588);
				currentBestLocation.setLongitude(-80.537426);
		           locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER,
		        		   currentBestLocation);

        		to = new GeoPoint(43476849, -80540408);
        		if(retrieveDirections != null && retrieveDirections.isAlive()) {
        			retrieveDirections.stop();
        		}
        		
        		mLP = new MockLocationProvider(locationManager, LocationManager.GPS_PROVIDER, data);
        		
                retrieveDirections = new Thread (new Runnable() {
                	public void run() {
                		simulation = true;
                		RetrieveDirections();
                	}
                });
                
                retrieveDirections.start();
				
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
    };
    
    private OnClickListener getDirectionsListener = new OnClickListener() {
        public void onClick(View v) {
        	if(currentBestLocation != null && to != null) {
        		
        		//retrieveDirections thread should be stopped if it exists
        		if(retrieveDirections != null && retrieveDirections.isAlive()) {
        			retrieveDirections.stop();
        		}
        		
                retrieveDirections = new Thread (new Runnable() {
                	public void run() {
                		RetrieveDirections();
                	}
                });
        		
        		retrieveDirections.start();
        	} else if (currentBestLocation == null) {
        		directions.setText("Could not find your current location");
        	} else if (to == null) {
        		directions.setText("Please select a destination");
        	}
        }
    };
    
    
	
	//GeoPoint from = new GeoPoint(43465850, -80540030);
	//GeoPoint to = new GeoPoint(43473800, -80555680);
    private void RetrieveDirections() {
    	GeoPoint from = new GeoPoint((int)(currentBestLocation.getLatitude()*1E6),(int)(currentBestLocation.getLongitude()*1E6));
    	
		String url = makeUrl(from,to);	
		steps = new LinkedList<Step>();
		//get a factory
		SAXParserFactory spf = SAXParserFactory.newInstance();
		try {
			//get a new instance of parser
			SAXParser sp = spf.newSAXParser();
			//parse the input and also register a private class for call backs
			sp.parse(url, new StepsParserCallBacks(steps));
		}catch(SAXException se) {
			se.printStackTrace();
		}catch(ParserConfigurationException pce) {
			pce.printStackTrace();
		}catch (IOException ie) {
			ie.printStackTrace();
		}
		
		StringBuffer sb = new StringBuffer();
		for(int i = 0; i < steps.size(); i++) {
			sb.append("Step"+ (i+1)+":\n");
			sb.append(steps.get(i).toString());
			sb.append("\n");
		}
		directionsString = sb.toString();
		
    	runOnUiThread(new Runnable(){
			public void run() {
				updateDirections();
			}
    	});
    	 
        running = true;
        
        
        //turnByturn thread should be stopped if it exists
        if(turnByturn != null && turnByturn.isAlive()) {
        	turnByturn.stop();
        }
        
        turnByturn = new Thread(new Runnable() {
			public void run() {
				startApplication();
			}
        });
        
        turnByturn.start();
        
        if(simulation) {
        	mLP.start();
        	simulation = false;
        }
    }
    
    
    private String makeUrl(GeoPoint src, GeoPoint dest) {

        StringBuilder urlString = new StringBuilder();
        urlString.append("http://maps.googleapis.com/maps/api/directions/xml?");
        urlString.append("origin=");// from
        urlString.append(Double.toString((double) src.getLatitudeE6() / 1.0E6));
        urlString.append(",");
        urlString.append(Double.toString((double) src.getLongitudeE6() / 1.0E6));
        urlString.append("&destination=");// to
        urlString.append(Double.toString((double) dest.getLatitudeE6() / 1.0E6));
        urlString.append(",");
        urlString.append(Double.toString((double) dest.getLongitudeE6() / 1.0E6));
        urlString.append("&sensor=true");

        System.out.println(urlString.toString());
        //Log.d("xxx", "URL=" + urlString.toString());
        return urlString.toString();
    }
    
    void doBindService() {
		if (!mIsBound){
			bindService(new Intent(IRemoteService.class.getName()), mConnection,
					Context.BIND_AUTO_CREATE);
			mIsBound = true;
		} 
    }

	void doUnbindService() {
		if (mIsBound) {
            // If we have received the service, and hence registered with
            // it, then now is the time to unregister.
            if (mIRemoteService != null) {
                try {
                    mIRemoteService.unregisterCallback(mCallback);
                } catch (RemoteException e) {
                    // There is nothing special we need to do if the service
                    // has crashed.
                }
            }

            // Detach our existing connection.
            unbindService(mConnection);
            mIsBound = false;
        }
	}
    	
    	/**
    	* Handler of incoming messages from service.
    	*/
    	static class IncomingHandler extends Handler {
    	   @Override
    	   public void handleMessage(Message msg) {
    	       /*switch (msg.what) {
    	           case BipsService.MSG_SET_VALUE:
    	               //mCallbackText.setText("Received from service: " + msg.arg1);
    	               break;
    	           default:
    	               super.handleMessage(msg);
    	       }*/
    	   }
    	}

    	final IncomingHandler mHandler = new IncomingHandler();
    	/**
    	* Target we publish for clients to send messages to Incoming Handler.
    	*/
    	final Messenger mMessenger = new Messenger(mHandler);
    	
    	
    	/**
    	* Class for interacting with the main interface of the service.
    	*/
    	private ServiceConnection mConnection = new ServiceConnection() {
    		   public void onServiceConnected(ComponentName className, IBinder service) {
    		        // Following the example above for an AIDL interface,
    		        // this gets an instance of the IRemoteInterface, which we can use to call on the service
    		        mIRemoteService = IRemoteService.Stub.asInterface(service);

    				// We want to monitor the service for as long as we are
    				// connected to it.
    				try {
    					mIRemoteService.registerCallback(mCallback);
    				} catch (RemoteException e) {
    					// In this case the service has crashed before we could even
    					// do anything with it; we can count on soon being
    					// disconnected (and then reconnected if it can be restarted)
    					// so there is no need to do anything here.
    				}

    		    }

    		    // Called when the connection with the service disconnects unexpectedly
    		    public void onServiceDisconnected(ComponentName className) {
    		        mIRemoteService = null;
    		    }
    	};
    	

        // ----------------------------------------------------------------------
        // Code showing how to deal with callbacks.
        // ----------------------------------------------------------------------

        /**
         * This implementation is used to receive callbacks from the remote
         * service.
         */
        private IRemoteServiceCallback mCallback = new IRemoteServiceCallback.Stub() {
        	public int getPid(){
                return Process.myPid();
            }
        	
        	/**
             * This is called by the remote service regularly to tell us about
             * new values.  Note that IPC calls are dispatched through a thread
             * pool running in each process, so the code executing here will
             * NOT be running in our main thread like most other things -- so,
             * to update the UI, we need to use a Handler to hop over there.
             */
            public void valueChanged(int value) {
                mHandler.sendMessage(mHandler.obtainMessage(0, value, 0));
            }
        };

    
}


class StepsParserCallBacks extends DefaultHandler {
	private LinkedList<Step> lst;
	
	private String tmpVal;
	
	private String polyline;
	private int distance;
	
	private float lng;
	private float lat;
	
	private float lng1;
	private float lat1;
	
	private float lng2;
	private float lat2;
	
	private String instructions;
	private boolean instr = false;
	
	
	public StepsParserCallBacks(LinkedList<Step> lst) {
		this.lst = lst;
	}
	
	public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
		if (qName.equalsIgnoreCase("html_instructions")) {
			this.instr = true;
			this.instructions = "";
		} else if (qName.equalsIgnoreCase("distance")) {
			this.distance = -1;
		}
	}
	
	public void characters(char[] ch, int start, int length) throws SAXException {
		this.tmpVal = new String(ch, start, length);
		if(instr) {
			this.instructions += this.tmpVal;
		}
	}
	
	public void endElement(String uri, String localName, String qName) throws SAXException {
		try {
			//(int d, String i, float lat1, float long1, float lat2, float long2)
		if (qName.equalsIgnoreCase("step")) {
			lst.add(new Step(this.distance, this.instructions, this.lat1, this.lng1, this.lat2, this.lng2, this.polyline));
		} else if (qName.equalsIgnoreCase("start_location")) {
			this.lng1 = this.lng;
			this.lat1 = this.lat;
		} else if (qName.equalsIgnoreCase("end_location")) {
			this.lng2 = this.lng;
			this.lat2 = this.lat;
		} else if (qName.equalsIgnoreCase("lat")) {
			this.lat = Float.parseFloat(this.tmpVal);
		} else if (qName.equalsIgnoreCase("lng")) {
			this.lng = Float.parseFloat(this.tmpVal);
		} else if (qName.equalsIgnoreCase("value") && this.distance == -1) {
			this.distance = Integer.parseInt(this.tmpVal);
		} else if (qName.equalsIgnoreCase("b")) {
			this.instructions+= this.tmpVal;
		} else if (qName.equalsIgnoreCase("div")) {
			this.instructions+= this.tmpVal;
		} else if (qName.equalsIgnoreCase("html_instructions")) {
			this.instr = false;
		} else if (qName.equalsIgnoreCase("points")) {
			this.polyline = this.tmpVal;
		}
		
		} catch (StringIndexOutOfBoundsException e) {
			System.out.println("String Index out of bounds");
			System.out.println("  tmpVal = " + this.tmpVal + " qname = " + qName);
			e.printStackTrace();
		}
	}
}