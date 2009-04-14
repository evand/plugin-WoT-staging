/*
 * This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL.
 */
package plugins.WoT.introduction;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import plugins.WoT.CurrentTimeUTC;
import plugins.WoT.Identity;
import plugins.WoT.OwnIdentity;
import plugins.WoT.WoT;
import plugins.WoT.exceptions.InvalidParameterException;

import com.db4o.ObjectContainer;
import com.db4o.ObjectSet;
import com.db4o.query.Query;

import freenet.keys.FreenetURI;
import freenet.support.Base64;
import freenet.support.Logger;

public final class IntroductionPuzzle {
	
	public static enum PuzzleType { Captcha };
	
	public static final String INTRODUCTION_CONTEXT = "introduction"; /* FIXME: Change to uppercase before release */
	public static final int MINIMAL_SOLUTION_LENGTH = 5;
	
	private static final SimpleDateFormat mDateFormat = new SimpleDateFormat("yyyy-MM-dd");
	
	/* Included in XML: */
	
	/**
	 * The ID of the puzzle is constructed as the concatenation of the  ID of the inserter and a random UUID
	 * This has to be done to prevent malicious users from inserting puzzles with the IDs of puzzles which someone else has already inserted.
	 */
	private final String mID;
	
	private final PuzzleType mType;
	
	private final String mMimeType;
	
	private final long mValidUntilTime;
	
	private final byte[] mData;
	
	/* Not included in XML, decoded from URI: */
	
	private final Identity mInserter;
	
	private final Date mDateOfInsertion;
	
	private final int mIndex;
	
	/* Supplied at creation time or by user: */

	/**
	 * We store the solver of the puzzle so that we can insert the solution even if the node is shutdown directly after solving puzzles.
	 */
	private OwnIdentity mSolver = null;
	
	private String mSolution = null;


	/**
	 * Set to true after it was used for introducing a new identity. We keep used puzzles in the database until they expire for the purpose of
	 * being able to figure out free index values of new puzzles. Storing a few KiB for some days will not hurt.
	 */
	private boolean iWasSolved = false;
	
	
	/* FIXME: wire this in */
	/**
	 * Get a list of fields which the database should create an index on.
	 */
	public static String[] getIndexedFields() {
		return new String[] {"mID", "mInserter"};
	}
	
	/**
	 * For construction from a received puzzle.
	 * @param newType
	 * @param newData
	 */
	public IntroductionPuzzle(Identity newInserter, String newID, PuzzleType newType, String newMimeType, byte[] newData, long myValidUntilTime, Date myDateOfInsertion, int myIndex) {
		assert(	newInserter != null && newID != null && newType != null && newMimeType != null && !newMimeType.equals("") &&
				newData!=null && newData.length!=0 && myValidUntilTime > CurrentTimeUTC.getInMillis() && myDateOfInsertion != null &&
				myDateOfInsertion.getTime() < CurrentTimeUTC.getInMillis()&& myIndex >= 0);
		
		mID = newID;
		mInserter = newInserter;
		mType = newType;
		mMimeType = newMimeType;
		mData = newData;
		mSolution = null;
		mDateOfInsertion = new Date(myDateOfInsertion.getYear(), myDateOfInsertion.getMonth(), myDateOfInsertion.getDate());
		mValidUntilTime = myValidUntilTime;
		mIndex = myIndex;
		
		if(checkConsistency() == false)
			throw new IllegalArgumentException("Corrupted puzzle received.");
	}
	
	/**
	 * For construction of a puzzle which is meant to be inserted.
	 * @param newType
	 * @param newData
	 */
	public IntroductionPuzzle(Identity newInserter, PuzzleType newType, String newMimeType, byte[] newData, String newSolution, Date newDateOfInsertion, int myIndex) {
		this(newInserter, newInserter.getId() + UUID.randomUUID().toString(), newType, newMimeType, newData, newDateOfInsertion.getTime() + IntroductionServer.PUZZLE_INVALID_AFTER_DAYS * 24 * 60 * 60 * 1000, newDateOfInsertion, myIndex);
		
		assert(newSolution!=null && newSolution.length()>=MINIMAL_SOLUTION_LENGTH);
		
		mSolution = newSolution;
		
		if(checkConsistency() == false)
			throw new IllegalArgumentException("Trying to costruct a corrupted puzzle");
	}
	
	public static IntroductionPuzzle getByID(ObjectContainer db, String id) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mID").constrain(id);
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		assert(result.size() <= 1);
		
		return (result.hasNext() ? result.next() : null);
	}
	
	/**
	 * Used by the IntroductionServer for downloading solutions.
	 * @param db
	 * @param i
	 * @return
	 */
	public static ObjectSet<IntroductionPuzzle> getByInserter(ObjectContainer db, Identity i) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").constrain(i);
		q.descend("iWasSolved").constrain(false);
		return q.execute();
	}
	
	/**
	 * Get puzzles which are from today. FIXME: Add a integer parameter to specify the age in days.
	 * Used by for checking whether new puzzles have to be inserted / downloaded.
	 */
	@SuppressWarnings("deprecation")
	public static List<IntroductionPuzzle> getRecentByInserter(ObjectContainer db, Identity i) {
		Date maxAge = new Date(CurrentTimeUTC.getYear()-1900, CurrentTimeUTC.getMonth(), CurrentTimeUTC.getDayOfMonth());
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").constrain(i);
		q.descend("mDateOfInsertion").constrain(maxAge).smaller().not();
		return q.execute();
	}
	
	public static IntroductionPuzzle getByRequestURI(ObjectContainer db, FreenetURI uri) throws ParseException {
		String[] tokens = uri.getDocName().split("[|]");
		Date date;
		synchronized (mDateFormat) {
			date = mDateFormat.parse(tokens[2]);
		}
		int index = Integer.parseInt(tokens[3]);
		
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").descend("id").constrain(Identity.getIdFromURI(uri));
		q.descend("mDateOfInsertion").constrain(date);
		q.descend("mIndex").constrain(index);
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		assert(result.size() <= 1);
		
		return (result.hasNext() ? result.next() : null);
	}
	
	public static IntroductionPuzzle getByInserterDateIndex(ObjectContainer db, Identity inserter, Date date, int index) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").descend("id").constrain(inserter.getId());
		q.descend("mDateOfInsertion").constrain(date);
		q.descend("mIndex").constrain(index);
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		assert(result.size() <= 1);
		
		return (result.hasNext() ? result.next() : null);
	}
	
	 /**
	  * Used by the IntroductionServer when a solution was downloaded to retrieve the IntroductionPuzzle object.
	  * @param db
	  * @param uri
	  * @return
	  * @throws ParseException
	  */
	public static IntroductionPuzzle getBySolutionURI(ObjectContainer db, FreenetURI uri) throws ParseException {
		String id = uri.getDocName().split("[|]")[2];
	
		return getByID(db, id);
	}
	
	/**
	 * Used by the IntroductionServer for inserting new puzzles.
	 * @param db
	 * @param id
	 * @param date
	 * @return
	 */
	public static int getFreeIndex(ObjectContainer db, OwnIdentity id, Date date) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mInserter").descend("id").constrain(id.getId());
		q.descend("mDateOfInsertion").constrain(new Date(date.getYear(), date.getMonth(), date.getDate()));
		q.descend("mIndex").orderDescending();
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		return result.size() > 0 ? result.next().getIndex()+1 : 0;
	}

	/**
	 * Used by the IntroductionClient for inserting solutions of solved puzzles.
	 * @param db
	 * @return
	 */
	public static ObjectSet<IntroductionPuzzle> getSolvedPuzzles(ObjectContainer db) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mSolver").constrain(null).identity().not();
		return q.execute();
	}
	
	public String getID() {
		return mID;
	}
	
	public PuzzleType getType() {
		return mType;
	}

	public String getMimeType() {
		return mMimeType;
	}

	/**
	 * Get the URI at which to insert this puzzle.
	 * SSK@asdfasdf...|WoT|introduction|yyyy-MM-dd|#
	 * 
	 * # = index of the puzzle.
	 */
	public FreenetURI getInsertURI() {
		assert(mSolution != null); /* This function should only be needed by the introduction server, not by clients. */
		
		/* FIXME: I did not really understand the javadoc of FreenetURI. Please verify that the following code actually creates an URI
		 * which looks like the one I specified in the javadoc above this function. Thanks. */
		String dayOfInsertion;
		synchronized (mDateFormat) {
			dayOfInsertion = mDateFormat.format(mDateOfInsertion);
		}
		FreenetURI baseURI = ((OwnIdentity)mInserter).getInsertURI().setKeyType("SSK");
		baseURI = baseURI.setDocName(WoT.WOT_NAME + "|" + INTRODUCTION_CONTEXT + "|" + dayOfInsertion + "|" + mIndex);
		return baseURI.setMetaString(null);
	}

	public FreenetURI getRequestURI() {
		return generateRequestURI(mInserter, mDateOfInsertion, mIndex);
	}

	public static FreenetURI generateRequestURI(Identity inserter, Date dateOfInsertion, int index) {
		assert(dateOfInsertion.before(CurrentTimeUTC.get()));
		assert(index >= 0);
		
		/* FIXME: I did not really understand the javadoc of FreenetURI. Please verify that the following code actually creates an URI
		 * which looks like the one I specified in the javadoc above this function. Thanks. */
		String dayOfInsertion;
		synchronized (mDateFormat) {
			dayOfInsertion = mDateFormat.format(dateOfInsertion);
		}
		FreenetURI baseURI = inserter.getRequestURI().setKeyType("SSK");
		baseURI = baseURI.setDocName(WoT.WOT_NAME + "|" + INTRODUCTION_CONTEXT + "|" + dayOfInsertion + "|" + index);
		return baseURI.setMetaString(null);
	}

	public static Date getDateFromRequestURI(FreenetURI requestURI) throws ParseException {
		String tokens[] = requestURI.getDocName().split("[|]");
		synchronized (mDateFormat) {
			return mDateFormat.parse(tokens[2]);
		}
	}

	public static int getIndexFromRequestURI(FreenetURI requestURI) {
		String tokens[] = requestURI.getDocName().split("[|]");
		return Integer.parseInt(tokens[3]);
	}

	/**
	 * Get the URI at which to look for a solution of this puzzle (if someone solved it)
	 */
	public FreenetURI getSolutionURI() {
		return getSolutionURI(mSolution);
	}
	
	/**
	 * Get the URI at which to insert the solution of this puzzle.
	 */
	public FreenetURI getSolutionURI(String guessOfSolution) {
		return new FreenetURI("KSK",	WoT.WOT_NAME + "|" +
										INTRODUCTION_CONTEXT + "|" +
										mID + "|" +	/* Do not include the ID of the inserter in the URI because the puzzle-ID already contains it. */
										guessOfSolution); /* FIXME: hash the solution!! */
	}
	
	public byte[] getData() {
		return mData;
	}
	
	public String getDataBase64() {
		return Base64.encodeStandard(mData);
	}
	
	/* TODO: This function probably does not need to be synchronized because the current "outside" code will not use it without locking.
	 * However, if one only knows this class and not how it is used by the rest, its logical to synchronize it. */
	/**
	 * Get the solution of the puzzle. Null if the puzzle was received and not locally generated.
	 */
	public synchronized String getSolution() {
		assert(mSolution != null); /* Whoever uses this function should not need to call it when there is no solution available */
		return mSolution;
	}

	/* TODO: This function probably does not need to be synchronized because the current "outside" code will not use it without locking.
	 * However, if one only knows this class and not how it is used by the rest, its logical to synchronize it. */
	/**
	 * Get the OwnIdentity which solved this puzzle. Used by the IntroductionClient for inserting solutions.
	 */
	public synchronized OwnIdentity getSolver() {
		assert(mSolver != null);
		return mSolver;
	}
	
	/**
	 * Used by the IntroductionServer to mark a puzzle as solved.
	 */
	public synchronized void setSolved() {
		iWasSolved = true;
	}
	
	/* TODO: This function probably does not need to be synchronized because the current "outside" code will not use it without locking.
	 * However, if one only knows this class and not how it is used by the rest, its logical to synchronize it. */
	/**
	 * Used by the IntroductionClient to mark a puzzle as solved
	 * @param solver
	 * @param solution
	 * @throws InvalidParameterException If the puzzle was already solved.
	 */
	public synchronized void setSolved(OwnIdentity solver, String solution) throws InvalidParameterException {
		if(iWasSolved == true)
			throw new InvalidParameterException("Puzzle is already solved!"); /* TODO: create a special exception for that */
		
		iWasSolved = true;
		mSolver = solver;
		mSolution = solution;
	}
	
	public Identity getInserter() {
		return mInserter;
	}
	
	public Date getDateOfInsertion() {
		return mDateOfInsertion;
	}
	
	public long getValidUntilTime() {
		return mValidUntilTime;
	}
	
	public int getIndex() {
		return mIndex;
	}
	
	public synchronized void store(ObjectContainer db) {
		/* TODO: Convert to debug code maybe when we are sure that this does not happen. Duplicate puzzles will be deleted after they
		 * expire anyway. Further, isn't there a db4o option which ensures that mID is a primary key and therefore no duplicates can exist? */
		IntroductionPuzzle existing = IntroductionPuzzle.getByID(db, mID);
		if(existing != null && existing != this)
			throw new IllegalArgumentException("Puzzle with ID " + mID + " already exists!");
		
		db.store(mType);
		// db.store(mDateOfInsertion); /* Not stored because it is a primitive for db4o */ 
		db.store(this);
		db.commit();
	}
	
	public static void deleteExpiredPuzzles(ObjectContainer db) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mValidUntilTime").constrain(CurrentTimeUTC.getInMillis()).smaller();
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		Logger.debug(IntroductionPuzzle.class, "Deleting " + result.size() + " expired puzzles.");
		for(IntroductionPuzzle p : result)
			db.delete(p);
		
		db.commit();
	}
	
	/**
	 * Used by the introduction client to delete old puzzles and replace them with new ones.
	 * @param db
	 * @param puzzlePoolSize
	 */
	public static void deleteOldestPuzzles(ObjectContainer db, int puzzlePoolSize) {
		Query q = db.query();
		q.constrain(IntroductionPuzzle.class);
		q.descend("mSolution").constrain(null).identity(); /* FIXME: toad said constrain(null) is maybe broken. If this is true: Alternative would be: q.descend("mIdentity").constrain(OwnIdentity.class).not(); */
		q.descend("mValidUntilTime").orderAscending();
		ObjectSet<IntroductionPuzzle> result = q.execute();
		
		int deleteCount = result.size() - puzzlePoolSize;
		
		Logger.debug(IntroductionPuzzle.class, "Deleting " + deleteCount + " old puzzles.");
		while(deleteCount > 0) {
			db.delete(result.next());
			deleteCount--;
		}
		
		db.commit();
	}
	
	/* TODO: Write an unit test which uses this function :) */
	/* FIXME: check for validity of the jpeg */
	
	public boolean checkConsistency() {
		boolean result = true;
		if(mID == null) 
			{ Logger.error(this, "mID == null!"); result = false; }
		else { /* Verify the UID */
			if(mInserter != null) {
				String inserterID = mInserter.getId();
				if(mID.startsWith(inserterID) == false) { Logger.error(this, "mID does not start with InserterID: " + mID); result = false; }
				/* Verification that the rest of the ID is an UUID is not necessary: If a client inserts a puzzle with the ID just being his
				 * identity ID (or other bogus stuff) he will just shoot himself in the foot by possibly only allowing 1 puzzle of him to
				 * be available because the databases of the downloaders check whether the ID already exists. */
			}
		}
		if(mType == null)
			{ Logger.error(this, "mType == null!"); result = false; }
		if(mMimeType == null || !mMimeType.equals("image/jpeg"))
			{ Logger.error(this, "mMimeType == " + mMimeType); result = false; }
		if(new Date(mValidUntilTime).before(new Date(2008-1900, 10, 10)))
			{ Logger.error(this, "mValidUntilTime == " + new Date(mValidUntilTime)); result = false; }
		if(mData == null || mData.length<100)
			{ Logger.error(this, "mData == " + mData); result = false; }
		if(mInserter == null)
			{ Logger.error(this, "mInserter == null"); result = false; }
		if(mDateOfInsertion == null || mDateOfInsertion.before(new Date(2008-1900, 10, 10)) || mDateOfInsertion.after(CurrentTimeUTC.get()))
			{ Logger.error(this, "mDateOfInsertion == " + mDateOfInsertion + "currentTime == " + CurrentTimeUTC.get()); result = false; }
		if(mIndex < 0)
			{ Logger.error(this, "mIndex == " + mIndex); result = false; }
		if(iWasSolved == true && (mSolver == null || mSolution == null))
			{ Logger.error(this, "iWasSolved but mSolver == " + mSolver + ", " + "mSolution == " + mSolution); result = false; }
		
		return result;
	}
}
