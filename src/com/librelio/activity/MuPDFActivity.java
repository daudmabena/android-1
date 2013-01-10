package com.librelio.activity;

import java.util.ArrayList;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.RectF;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.Log;
import android.util.SparseArray;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.ViewSwitcher;

import com.artifex.mupdf.LinkInfo;
import com.artifex.mupdf.MediaHolder;
import com.artifex.mupdf.MuPDFCore;
import com.artifex.mupdf.MuPDFPageAdapter;
import com.artifex.mupdf.MuPDFPageView;
import com.artifex.mupdf.PDFPreviewPagerAdapter;
import com.artifex.mupdf.PageView;
import com.artifex.mupdf.ReaderView;
import com.artifex.mupdf.domain.OutlineActivityData;
import com.artifex.mupdf.domain.OutlineItem;
import com.artifex.mupdf.domain.SearchTaskResult;
import com.librelio.base.BaseActivity;
import com.librelio.lib.ui.SlideShowActivity;
import com.librelio.lib.utils.PDFParser;
import com.librelio.model.Magazine;
import com.librelio.storage.MagazineManager;
import com.librelio.task.SafeAsyncTask;
import com.librelio.view.HorizontalListView;
import com.librelio.view.ProgressDialogX;
import com.niveales.wind.R;

//TODO: remove preffix mXXXX from all properties this class
public class MuPDFActivity extends BaseActivity {
	private static final String TAG = "MuPDFActivity";

	private static final int TAP_PAGE_MARGIN = 70;
	private static final int SEARCH_PROGRESS_DELAY = 200;
	private static final String FILE_NAME = "FileName";

	private enum LinkState {
		DEFAULT, HIGHLIGHT, INHIBIT
	};

	public interface RecycleObserver {
		public void recycle();
	}

	private MuPDFCore core;
	private String fileName;
	private int mOrientation;

	private AlertDialog.Builder alertBuilder;
	private ReaderView   docView;
	private View         mButtonsView;
	private boolean      mButtonsVisible;
	private EditText     mPasswordView;
	private TextView     mFilenameView;
//	private SeekBar      mPageSlider;
	private int          mPageSliderRes;
//	private TextView     mPageNumberView;
	private ImageButton  mSearchButton;
	private ImageButton  mCancelButton;
	private ImageButton  mOutlineButton;
	private ViewSwitcher mTopBarSwitcher;
// XXX	private ImageButton  mLinkButton;
	private boolean      mTopBarIsSearch;
	private ImageButton  mSearchBack;
	private ImageButton  mSearchFwd;
	private EditText     mSearchText;
	private SafeAsyncTask<Void,Integer,SearchTaskResult> mSearchTask;
	//private SearchTaskResult mSearchTaskResult;
	private LinkState    mLinkState = LinkState.DEFAULT;
	private final Handler mHandler = new Handler();
	private FrameLayout mPreviewBarHolder;
	private HorizontalListView mPreview;
	private MuPDFPageAdapter mDocViewAdapter;
	private SparseArray<LinkInfo[]> linkOfDocument;
	private static RecycleObserver observer;

	public static void setObserver(RecycleObserver ro){
		observer = ro;
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);

		alertBuilder = new AlertDialog.Builder(this);
	
		core = getMuPdfCore(savedInstanceState);
	
		if (core == null) {
			return;
		}
	
		mOrientation = getResources().getConfiguration().orientation;

		if(mOrientation == Configuration.ORIENTATION_LANDSCAPE) {
			core.setDisplayPages(2);
		} else {
			core.setDisplayPages(1);
		}

		createUI(savedInstanceState);
	}

	private void requestPassword(final Bundle savedInstanceState) {
		mPasswordView = new EditText(this);
		mPasswordView.setInputType(EditorInfo.TYPE_TEXT_VARIATION_PASSWORD);
		mPasswordView.setTransformationMethod(new PasswordTransformationMethod());

		AlertDialog alert = alertBuilder.create();
		alert.setTitle(R.string.enter_password);
		alert.setView(mPasswordView);
		alert.setButton(AlertDialog.BUTTON_POSITIVE, getResources().getString(R.string.ok),
				new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int which) {
				if (core.authenticatePassword(mPasswordView.getText().toString())) {
					createUI(savedInstanceState);
				} else {
					requestPassword(savedInstanceState);
				}
			}
		});
		alert.setButton(AlertDialog.BUTTON_NEGATIVE, getResources().getString(R.string.cancel),
				new DialogInterface.OnClickListener() {

			public void onClick(DialogInterface dialog, int which) {
				finish();
			}
		});
		alert.show();
	}

	private MuPDFCore getMuPdfCore(Bundle savedInstanceState) {
		MuPDFCore core = null;
		if (core == null) {
			core = (MuPDFCore)getLastNonConfigurationInstance();

			if (savedInstanceState != null && savedInstanceState.containsKey(FILE_NAME)) {
				fileName = savedInstanceState.getString(FILE_NAME);
			}
		}
		if (core == null) {
			Intent intent = getIntent();
			if (Intent.ACTION_VIEW.equals(intent.getAction())) {
				Uri uri = intent.getData();
				if (uri.toString().startsWith("content://media/external/file")) {
					// Handle view requests from the Transformer Prime's file manager
					// Hopefully other file managers will use this same scheme, if not
					// using explicit paths.
					Cursor cursor = getContentResolver().query(uri, new String[]{"_data"}, null, null, null);
					if (cursor.moveToFirst()) {
						uri = Uri.parse(cursor.getString(0));
					}
				}

				core = openFile(Uri.decode(uri.getEncodedPath()));
				SearchTaskResult.recycle();
			}
			if (core != null && core.needsPassword()) {
				requestPassword(savedInstanceState);
				return null;
			}
		}
		if (core == null) {
			AlertDialog alert = alertBuilder.create();
			
			alert.setTitle(R.string.open_failed);
			alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dismiss),
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							finish();
						}
					});
			alert.show();
			return null;
		}
		return core;
	}

	private void createUI(Bundle savedInstanceState) {
		if (core == null)
			return;
		// Now create the UI.
		// First create the document view making use of the ReaderView's internal
		// gesture recognition
		docView = new DocumentReaderView(this, linkOfDocument);
		mDocViewAdapter = new MuPDFPageAdapter(this, core);
		docView.setAdapter(mDocViewAdapter);

		// Make the buttons overlay, and store all its
		// controls in variables
		makeButtonsView();

		// Set up the page slider
		int smax = Math.max(core.countPages()-1,1);
		mPageSliderRes = ((10 + smax - 1)/smax) * 2;

		// Set the file-name text
		mFilenameView.setText(fileName);

		// Activate the seekbar
//		mPageSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
//			public void onStopTrackingTouch(SeekBar seekBar) {
//				mDocView.setDisplayedViewIndex((seekBar.getProgress()+mPageSliderRes/2)/mPageSliderRes);
//			}
//
//			public void onStartTrackingTouch(SeekBar seekBar) {}
//
//			public void onProgressChanged(SeekBar seekBar, int progress,
//					boolean fromUser) {
//				updatePageNumView((progress+mPageSliderRes/2)/mPageSliderRes);
//			}
//		});

		// Activate the search-preparing button
		mSearchButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				searchModeOn();
			}
		});

		mCancelButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				searchModeOff();
			}
		});

		// Search invoking buttons are disabled while there is no text specified
		mSearchBack.setEnabled(false);
		mSearchFwd.setEnabled(false);

		// React to interaction with the text widget
		mSearchText.addTextChangedListener(new TextWatcher() {

			public void afterTextChanged(Editable s) {
				boolean haveText = s.toString().length() > 0;
				mSearchBack.setEnabled(haveText);
				mSearchFwd.setEnabled(haveText);

				// Remove any previous search results
				if (SearchTaskResult.get() != null && !mSearchText.getText().toString().equals(SearchTaskResult.get().txt)) {
					SearchTaskResult.recycle();
					docView.resetupChildren();
				}
			}
			public void beforeTextChanged(CharSequence s, int start, int count,
					int after) {}
			public void onTextChanged(CharSequence s, int start, int before,
					int count) {}
		});

		//React to Done button on keyboard
		mSearchText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
			public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
				if (actionId == EditorInfo.IME_ACTION_DONE)
					search(1);
				return false;
			}
		});

		mSearchText.setOnKeyListener(new View.OnKeyListener() {
			public boolean onKey(View v, int keyCode, KeyEvent event) {
				if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER)
					search(1);
				return false;
			}
		});

		// Activate search invoking buttons
		mSearchBack.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				search(-1);
			}
		});
		mSearchFwd.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				search(1);
			}
		});

		if (core.hasOutline()) {
			mOutlineButton.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					OutlineItem outline[] = core.getOutline();
					if (outline != null) {
						OutlineActivityData.get().items = outline;
						Intent intent = new Intent(MuPDFActivity.this, OutlineActivity.class);
						startActivityForResult(intent, 0);
					}
				}
			});
		} else {
			mOutlineButton.setVisibility(View.GONE);
		}

		// Reenstate last state if it was recorded
		SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
		int orientation = prefs.getInt("orientation", mOrientation);
		int pageNum = prefs.getInt("page"+fileName, 0);
		if(orientation == mOrientation)
			docView.setDisplayedViewIndex(pageNum);
		else {
			if(orientation == Configuration.ORIENTATION_PORTRAIT) {
				docView.setDisplayedViewIndex((pageNum + 1) / 2);
			} else {
				docView.setDisplayedViewIndex((pageNum == 0) ? 0 : pageNum * 2 - 1);
			}
		}

		if (savedInstanceState == null || !savedInstanceState.getBoolean("ButtonsHidden", false))
			showButtons();

		if(savedInstanceState != null && savedInstanceState.getBoolean("SearchMode", false))
			searchModeOn();

		// Stick the document view and the buttons overlay into a parent view
		RelativeLayout layout = new RelativeLayout(this);
		layout.addView(docView);
		layout.addView(mButtonsView);
//		layout.setBackgroundResource(R.drawable.tiled_background);
		//layout.setBackgroundResource(R.color.canvas);
		layout.setBackgroundColor(Color.BLACK);
		setContentView(layout);
	}

	
	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		if (resultCode >= 0)
			docView.setDisplayedViewIndex(resultCode);
		super.onActivityResult(requestCode, resultCode, data);
	}

	public Object onRetainNonConfigurationInstance() {
		MuPDFCore mycore = core;
		core = null;
		return mycore;
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);

		if (fileName != null && docView != null) {
			outState.putString("FileName", fileName);

			// Store current page in the prefs against the file name,
			// so that we can pick it up each time the file is loaded
			// Other info is needed only for screen-orientation change,
			// so it can go in the bundle
			SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
			SharedPreferences.Editor edit = prefs.edit();
			edit.putInt("page"+fileName, docView.getDisplayedViewIndex());
			edit.putInt("orientation", mOrientation);
			edit.commit();
		}

		if (!mButtonsVisible)
			outState.putBoolean("ButtonsHidden", true);

		if (mTopBarIsSearch)
			outState.putBoolean("SearchMode", true);
	}

	@Override
	protected void onPause() {
		super.onPause();

		killSearch();

		if (fileName != null && docView != null) {
			SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
			SharedPreferences.Editor edit = prefs.edit();
			edit.putInt("page"+fileName, docView.getDisplayedViewIndex());
			edit.putInt("orientation", mOrientation);
			edit.commit();
		}
	}
	
	@Override
	public void onBackPressed() {
		if (observer != null) {
			observer.recycle();
		}
		super.onBackPressed();
	}

	@Override
	public void onDestroy() {
		if (core != null) {
			core.onDestroy();
		}
		core = null;
		super.onDestroy();
	}

	void showButtons() {
		if (core == null) {
			return;
		}
		if (!mButtonsVisible) {
			mButtonsVisible = true;
			// Update page number text and slider
			int index = docView.getDisplayedViewIndex();
			updatePageNumView(index);
//			mPageSlider.setMax((core.countPages()-1)*mPageSliderRes);
//			mPageSlider.setProgress(index*mPageSliderRes);
			if (mTopBarIsSearch) {
				mSearchText.requestFocus();
				showKeyboard();
			}

			Animation anim = new TranslateAnimation(0, 0, -mTopBarSwitcher.getHeight(), 0);
			anim.setDuration(200);
			anim.setAnimationListener(new Animation.AnimationListener() {
				public void onAnimationStart(Animation animation) {
					mTopBarSwitcher.setVisibility(View.VISIBLE);
				}
				public void onAnimationRepeat(Animation animation) {}
				public void onAnimationEnd(Animation animation) {}
			});
			mTopBarSwitcher.startAnimation(anim);
			// Update listView position
			mPreview.setSelection(docView.getDisplayedViewIndex());
			anim = new TranslateAnimation(0, 0, mPreviewBarHolder.getHeight(), 0);
			anim.setDuration(200);
			anim.setAnimationListener(new Animation.AnimationListener() {
				public void onAnimationStart(Animation animation) {
					mPreviewBarHolder.setVisibility(View.VISIBLE);
				}
				public void onAnimationRepeat(Animation animation) {}
				public void onAnimationEnd(Animation animation) {
				}
			});
			mPreviewBarHolder.startAnimation(anim);
		}
	}

	void hideButtons() {
		if (mButtonsVisible) {
			mButtonsVisible = false;
			hideKeyboard();

			Animation anim = new TranslateAnimation(0, 0, 0, -mTopBarSwitcher.getHeight());
			anim.setDuration(200);
			anim.setAnimationListener(new Animation.AnimationListener() {
				public void onAnimationStart(Animation animation) {}
				public void onAnimationRepeat(Animation animation) {}
				public void onAnimationEnd(Animation animation) {
					mTopBarSwitcher.setVisibility(View.INVISIBLE);
				}
			});
			mTopBarSwitcher.startAnimation(anim);
			
			anim = new TranslateAnimation(0, 0, 0, this.mPreviewBarHolder.getHeight());
			anim.setDuration(200);
			anim.setAnimationListener(new Animation.AnimationListener() {
				public void onAnimationStart(Animation animation) {
					mPreviewBarHolder.setVisibility(View.INVISIBLE);
				}
				public void onAnimationRepeat(Animation animation) {}
				public void onAnimationEnd(Animation animation) {
				}
			});
			mPreviewBarHolder.startAnimation(anim);
		}
	}

	void searchModeOn() {
		if (!mTopBarIsSearch) {
			mTopBarIsSearch = true;
			//Focus on EditTextWidget
			mSearchText.requestFocus();
			showKeyboard();
			mTopBarSwitcher.showNext();
		}
	}

	void searchModeOff() {
		if (mTopBarIsSearch) {
			mTopBarIsSearch = false;
			hideKeyboard();
			mTopBarSwitcher.showPrevious();
			SearchTaskResult.recycle();
			// Make the ReaderView act on the change to mSearchTaskResult
			// via overridden onChildSetup method.
			docView.resetupChildren();
		}
	}

	void updatePageNumView(int index) {
		if (core == null)
			return;
//		mPageNumberView.setText(String.format("%d/%d", index+1, core.countPages()));
	}

	void makeButtonsView() {
		mButtonsView = getLayoutInflater().inflate(R.layout.buttons,null);
		mFilenameView = (TextView)mButtonsView.findViewById(R.id.docNameText);
//		mPageSlider = (SeekBar)mButtonsView.findViewById(R.id.pageSlider);
		mPreviewBarHolder = (FrameLayout) mButtonsView.findViewById(R.id.PreviewBarHolder);
		mPreview = new HorizontalListView(this);
		FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
		mPreview.setLayoutParams(lp);
		mPreview.setAdapter(new PDFPreviewPagerAdapter(this, core));
		mPreview.setOnItemClickListener(new OnItemClickListener() {

			@Override
			public void onItemClick(AdapterView<?> pArg0, View pArg1,
					int position, long id) {
				// TODO Auto-generated method stub
				hideButtons();
				docView.setDisplayedViewIndex((int)id);
			}

		});
		mPreviewBarHolder.addView(mPreview);
//		Gallery mGallery = (Gallery) mButtonsView.findViewById(R.id.PreviewGallery);
//		mGallery.setAdapter(new PDFPreviewPagerAdapter(this, core));

//		mPageNumberView = (TextView)mButtonsView.findViewById(R.id.pageNumber);
		mSearchButton = (ImageButton)mButtonsView.findViewById(R.id.searchButton);
		mCancelButton = (ImageButton)mButtonsView.findViewById(R.id.cancel);
		mOutlineButton = (ImageButton)mButtonsView.findViewById(R.id.outlineButton);
		mTopBarSwitcher = (ViewSwitcher)mButtonsView.findViewById(R.id.switcher);
		mSearchBack = (ImageButton)mButtonsView.findViewById(R.id.searchBack);
		mSearchFwd = (ImageButton)mButtonsView.findViewById(R.id.searchForward);
		mSearchText = (EditText)mButtonsView.findViewById(R.id.searchText);
// XXX		mLinkButton = (ImageButton)mButtonsView.findViewById(R.id.linkButton);
		mTopBarSwitcher.setVisibility(View.INVISIBLE);
//		mPageNumberView.setVisibility(View.INVISIBLE);
//		mPageSlider.setVisibility(View.INVISIBLE);
		mPreviewBarHolder.setVisibility(View.INVISIBLE);
	}

	void showKeyboard() {
		InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null)
			imm.showSoftInput(mSearchText, 0);
	}

	void hideKeyboard() {
		InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null)
			imm.hideSoftInputFromWindow(mSearchText.getWindowToken(), 0);
	}

	void killSearch() {
		if (mSearchTask != null) {
			mSearchTask.cancel(true);
			mSearchTask = null;
		}
	}

	void search(int direction) {
		hideKeyboard();
		if (core == null)
			return;
		killSearch();

		final int increment = direction;
		final int startIndex = SearchTaskResult.get() == null ? docView.getDisplayedViewIndex() : SearchTaskResult.get().pageNumber + increment;

		final ProgressDialogX progressDialog = new ProgressDialogX(this);
		progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
		progressDialog.setTitle(getString(R.string.searching_));
		progressDialog.setOnCancelListener(new DialogInterface.OnCancelListener() {
			public void onCancel(DialogInterface dialog) {
				killSearch();
			}
		});
		progressDialog.setMax(core.countPages());

		mSearchTask = new SafeAsyncTask<Void,Integer,SearchTaskResult>() {
			@Override
			protected SearchTaskResult doInBackground(Void... params) {
				int index = startIndex;

				while (0 <= index && index < core.countPages() && !isCancelled()) {
					publishProgress(index);
					RectF searchHits[] = core.searchPage(index, mSearchText.getText().toString());

					if (searchHits != null && searchHits.length > 0) {
						return SearchTaskResult.init(mSearchText.getText().toString(), index, searchHits);
					}

					index += increment;
				}
				return null;
			}

			@Override
			protected void onPostExecute(SearchTaskResult result) {
				progressDialog.cancel();
				if (result != null) {
					// Ask the ReaderView to move to the resulting page
					docView.setDisplayedViewIndex(result.pageNumber);
				    SearchTaskResult.recycle();
					// Make the ReaderView act on the change to mSearchTaskResult
					// via overridden onChildSetup method.
				    docView.resetupChildren();
				} else {
					alertBuilder.setTitle(SearchTaskResult.get() == null ? R.string.text_not_found : R.string.no_further_occurences_found);
					AlertDialog alert = alertBuilder.create();
					alert.setButton(AlertDialog.BUTTON_POSITIVE, "Dismiss",
							(DialogInterface.OnClickListener)null);
					alert.show();
				}
			}

			@Override
			protected void onCancelled() {
				super.onCancelled();
				progressDialog.cancel();
			}

			@Override
			protected void onProgressUpdate(Integer... values) {
				super.onProgressUpdate(values);
				progressDialog.setProgress(values[0].intValue());
			}

			@Override
			protected void onPreExecute() {
				super.onPreExecute();
				mHandler.postDelayed(new Runnable() {
					public void run() {
						if (!progressDialog.isCancelled())
						{
							progressDialog.show();
							progressDialog.setProgress(startIndex);
						}
					}
				}, SEARCH_PROGRESS_DELAY);
			}
		};

		mSearchTask.safeExecute();
	}

	@Override
	public boolean onSearchRequested() {
		if (mButtonsVisible && mTopBarIsSearch) {
			hideButtons();
		} else {
			showButtons();
			searchModeOn();
		}
		return super.onSearchRequested();
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		if (mButtonsVisible && !mTopBarIsSearch) {
			hideButtons();
		} else {
			showButtons();
			searchModeOff();
		}
		return super.onPrepareOptionsMenu(menu);
	}

	private MuPDFCore openFile(String path) {
		int lastSlashPos = path.lastIndexOf('/');
		fileName = new String(lastSlashPos == -1
					? path
					: path.substring(lastSlashPos+1));
		Log.d(TAG, "Trying to open " + path);
		PDFParser linkGetter = new PDFParser(path);
		linkOfDocument = linkGetter.getLinkInfo();
		Log.d(TAG,"link size = "+linkOfDocument.size());
		for(int i=0;i<linkOfDocument.size();i++){
			Log.d(TAG,"--- i = "+i);
			if(linkOfDocument.get(i)!=null){
				for(int j=0;j<linkOfDocument.get(i).length;j++){
					String link = linkOfDocument.get(i)[j].uri;
					Log.d(TAG,"link[" + j + "] = "+link);
					String local = "http://localhost";
					if(link.startsWith(local)){
						Log.d(TAG,"   link: "+link);
					}
				}
			}
		}
		try {
			core = new MuPDFCore(path);
			// New file: drop the old outline data
			OutlineActivityData.set(null);
		} catch (Exception e) {
			Log.e(TAG, "get core failed", e);
			return null;
		}
		return core;
	}

	/**
	 * @param linkString - url to open
	 */
	private void openLink(String linkString) {
		Log.d(TAG, "openLink " + linkString);
		Uri uri = Uri.parse(linkString);
		String warect = uri.getQueryParameter("warect");
		Boolean isFullScreen = warect != null && warect.equals("full");
		if(linkString.startsWith("http://localhost/")) {
			// display local content
			
			// get the current page view
			String path = uri.getPath();
			Log.d(TAG, "localhost path = " + path);
			if(path == null)
				return;
			
			if(path.endsWith("jpg") || path.endsWith("png") || path.endsWith("bmp")) {
				// start image slideshow
				Intent intent = new Intent(this, SlideShowActivity.class);
				intent.putExtra("path", path);
				intent.putExtra("uri", linkString);
				Log.d(TAG,"basePath = "+path+"\nuri = "+ linkString);
				//startActivity(intent);
			}
			if(path.endsWith("mp4") && isFullScreen) {
				// start a video player
				//Uri videoUri = Uri.parse("file://" + getStoragePath() + "/wind_355" + path);
				//Intent intent = new Intent(Intent.ACTION_VIEW, videoUri);
				//startActivity(intent);
			}
		} else if(linkString.startsWith("buy://localhost")) {
			onBuy(uri.getPath().substring(1));
		} else {
			//TODO: replace with custom activity
			Intent intent = new Intent(Intent.ACTION_VIEW, uri);
			startActivity(intent);
		}
		
	}

	private void onBuy(String path) {
		Log.d(TAG, "onBuy event path = " + path);
		MagazineManager magazineManager = new MagazineManager(getContext());
		Magazine magazine = magazineManager.findByFileName(path);
		if (null != magazine) {
			Intent intent = new Intent(getContext(), BillingActivity.class);
			intent
				.putExtra(DownloadActivity.FILE_NAME_KEY, magazine.getFileName())
				.putExtra(DownloadActivity.TITLE_KEY, magazine.getTitle())
				.putExtra(DownloadActivity.SUBTITLE_KEY, magazine.getSubtitle());
			getContext().startActivity(intent);
		}
	}

	private Context getContext() {
		return this;
	}

	private class ActivateAutoLinks extends AsyncTask<Integer, Void, ArrayList<LinkInfo>> {

		@Override
		protected ArrayList<LinkInfo> doInBackground(Integer... params) {
			int page = params[0].intValue();
			Log.d(TAG, "Page = " + page);
			if (null != core) {
				LinkInfo[] links = core.getPageLinks(page);
				if(null == links){
					return null;
				}
				ArrayList<LinkInfo> autoLinks = new ArrayList<LinkInfo>();
				for (LinkInfo link : links) {
					Log.d(TAG, "activateAutoLinks link: " + link.uri);
					if (null == link.uri) {
						continue;
					}
					if (link.isMediaURI()) {
						if (link.isAutoPlay()) {
							autoLinks.add(link);
						}
					}
				}
				return autoLinks;
			}
			return null;
		}

		@Override
		protected void onPostExecute(final ArrayList<LinkInfo> autoLinks) {
			if (isCancelled() || autoLinks == null) {
				return;
			}
			docView.post(new Runnable() {
				public void run() {
					for(LinkInfo link : autoLinks){
						MuPDFPageView pageView = (MuPDFPageView) docView.getDisplayedView();
						if (pageView != null && null != core) {
							String basePath = core.getFileDirectory();
							MediaHolder mediaHolder = new MediaHolder(getContext(), link, basePath);
							pageView.addMediaHolder(mediaHolder, link.uri);
							pageView.addView(mediaHolder);
							mediaHolder.setVisibility(View.VISIBLE);
							mediaHolder.requestLayout();
						}
					}
				}
			});
		}
	}

	private class DocumentReaderView extends ReaderView {
		private boolean showButtonsDisabled;

		public DocumentReaderView(Context context,
				SparseArray<LinkInfo[]> linkOfDocument) {
			super(context, linkOfDocument);
		}

		@Override
		public boolean onSingleTapUp(MotionEvent e) {
			if (e.getX() < TAP_PAGE_MARGIN) {
				super.moveToPrevious();
			} else if (e.getX() > super.getWidth() - TAP_PAGE_MARGIN) {
				super.moveToNext();
			} else if (!showButtonsDisabled) {
				int linkPage = -1;
				String linkString = null;
				if (mLinkState != LinkState.INHIBIT) {
					MuPDFPageView pageView = (MuPDFPageView) docView.getDisplayedView();
					if (pageView != null) {
						linkPage = pageView.hitLinkPage(e.getX(), e.getY());
						linkString = pageView.hitLinkUri(e.getX(),  e.getY());
					}
				}

				if (linkPage != -1) {
					docView.setDisplayedViewIndex(linkPage);
				} else if (linkString != null) {
					// start intent with url as linkString
					openLink(linkString);
				} else {
					if (!mButtonsVisible) {
						showButtons();
					} else {
						hideButtons();
					}
				}
			}
			return super.onSingleTapUp(e);
		}

		@Override
		public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
			if (!showButtonsDisabled) {
				hideButtons();
			}
			return super.onScroll(e1, e2, distanceX, distanceY);
		}

		@Override
		public boolean onScaleBegin(ScaleGestureDetector d) {
			// Disabled showing the buttons until next touch.
			// Not sure why this is needed, but without it
			// pinch zoom can make the buttons appear
			showButtonsDisabled = true;
			return super.onScaleBegin(d);
		}

		@Override
		public boolean onTouchEvent(MotionEvent event) {
			if (event.getActionMasked() == MotionEvent.ACTION_DOWN)
				showButtonsDisabled = false;

			return super.onTouchEvent(event);
		}

//		protected void onChildSetup(int i, View v) {
//			if (SearchTaskResult.get() != null && SearchTaskResult.get().pageNumber == i)
//				((PageView)v).setSearchBoxes(SearchTaskResult.get().searchBoxes);
//			else
//				((PageView)v).setSearchBoxes(null);
//
//			((PageView)v).setLinkHighlighting(mLinkState == LinkState.HIGHLIGHT);
//		}

		@Override
		protected void onMoveToChild(int i) {
			Log.d(TAG,"onMoveToChild id = "+i);
			//
			//
			if(observer!=null){
				observer.recycle();
			}
			if (core == null){
				return;
				
			} 
			new ActivateAutoLinks().execute(i);
//			mPageNumberView.setText(String.format("%d/%d", i+1, core.countPages()));
//			mPageSlider.setMax((core.countPages()-1) * mPageSliderRes);
//			mPageSlider.setProgress(i * mPageSliderRes);
			if (SearchTaskResult.get() != null && SearchTaskResult.get().pageNumber != i) {
				SearchTaskResult.recycle();
				docView.resetupChildren();
			}
		}

		@Override
		protected void onSettle(View v) {
			((PageView)v).addHq();
		}

		@Override
		protected void onUnsettle(View v) {
			((PageView)v).removeHq();
		}

		@Override
		protected void onNotInUse(View v) {
			((PageView)v).releaseResources();
		}
	};
}