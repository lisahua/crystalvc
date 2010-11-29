package crystal.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.SwingWorker;
import org.apache.log4j.Logger;
import crystal.client.ConflictDaemon.ComputationListener;
import crystal.model.DataSource;
import crystal.model.LocalStateResult;
import crystal.model.Relationship;
import crystal.model.Result;
import crystal.model.RevisionHistory;

/**
 * CalculateProjectTask is a worker that executes crystal tasks in the background thread while _STILL_ updating the UI. 
 * When Crystal does the analysis on a regular Thread, the UI won't update until all of the tasks are done; 
 * the UI doesn't block, but it doesn't update either. 
 * CalculateProjectTask fixes that problem.
 * 
 * @author brun
 */
public class CalculateProjectTask extends SwingWorker<Void, Result> {

	// The logger
	private Logger _log = Logger.getLogger(this.getClass());
	
	// The project whose relationships this CalculateProjectTask will calculate.  
	private ProjectPreferences _prefs;
	
	// The system tray listener to call when an update is ready.
	private ComputationListener _trayListener;
	
	// The frame listener to call when an update is ready.
	private ComputationListener _clientListener;

	/**
	 * Constructor.
	 * 
	 * @param prefs:  			the project whose relationships this CalculateProjectTask will calculate.
	 * @param trayListener: 	the system tray listener to call when an update is ready.
	 * @param clientListener: 	the frame listener to call when an update is ready.
	 */
	CalculateProjectTask(ProjectPreferences prefs, ComputationListener trayListener, ComputationListener clientListener) {
		_prefs = prefs;

		_trayListener = trayListener;
		_clientListener = clientListener;
	}

	/**
	 * Calculate the local state of the main repository in the project, 
	 * the action,
	 * the relationships with each other repository,  
	 * and the guidance for each relationship.
	 */
	@Override
	protected Void doInBackground() throws Exception {
		
		// First, do the local state.
		// We do this by checking the current local state, updating the GUI (mostly to show the pending icons).
		// And then performing the calculation and updating the GUI again.  
		
		// So, first check the current state:
		// UPDATE: turns out we don't have to do this.
//		LocalStateResult localStatePlaceholder = null;
//		if (ConflictDaemon.getInstance().getLocalState(_prefs.getEnvironment()) != null) {
//			localStatePlaceholder = new LocalStateResult(_prefs.getEnvironment(), LocalState.PENDING, ConflictDaemon.getInstance().getLocalState(_prefs.getEnvironment()).getLocalState());
//		} else {
//			localStatePlaceholder = new LocalStateResult(_prefs.getEnvironment(), LocalState.PENDING, null);
//		}
//
//		// Now update the GUI with current state:
//		publish(localStatePlaceholder);
		
		// Now calculate the new state:
		LocalStateResult localStateResult = ConflictDaemon.getInstance().calculateLocalState(_prefs);

		_log.trace("Local state computed: " + localStateResult);

		// UPDATE: turns out we don't have to do this.
		// And update the GUI
		publish(localStateResult);
		

		// Second, do the relationships.
		// We do this by checking the current local state, updating the GUI (mostly to show the pending icons).
		// And then performing the calculation for relationships.
		// And then calculating the Guidance and updating the relationships.
		// And finally updating the GUI.  

		// We'll store the relationships here:
		Map<DataSource, Relationship> relationships = new HashMap<DataSource, Relationship>(); 
		
		// UPDATE: turns out we don't have to do this.
//		// So, first check the current state.  
//		for (DataSource source : _prefs.getDataSources()) {
//			RelationshipResult relationshipPlaceholder = null;
//
//			if (ConflictDaemon.getInstance().getRelationship(source) != null) {
//				relationshipPlaceholder = new RelationshipResult(source, Relationship.PENDING, ConflictDaemon.getInstance().getRelationship(source).getRelationship());
//			} else {
//				relationshipPlaceholder = new RelationshipResult(source, Relationship.PENDING, null);
//			}
//			// And update the GUI with current relationship:
//			publish(relationshipPlaceholder);
//		}
		
		// And then perform the calculations for all the relationships:
		for (DataSource source : _prefs.getDataSources()) {
			Relationship relationshipResult = ConflictDaemon.getInstance().calculateRelationship(source, _prefs);
			relationships.put(source, relationshipResult);
		}
		
		// And then calculate the Guidance and update the relationships:
		RevisionHistory mine = _prefs.getEnvironment().getHistory();
		for (DataSource source : _prefs.getDataSources()) {
			RevisionHistory yours = source.getHistory();
			Relationship ourRelationship = relationships.get(source);
			// calculate the relevant Committers
			ourRelationship.setCommitters(mine.getCommitters(yours));
			
			DataSource parentSource = _prefs.getDataSource((_prefs.getEnvironment().getParent()));
			// If parent is not set, can't compute action
			if (parentSource != null) {
				Relationship parentRelationship = relationships.get(parentSource);
				ourRelationship.calculateAction(localStateResult.getLocalState(), parentRelationship);
			}
			
			DataSource commonParentSource = _prefs.getDataSource(source.getParent());
			// If commonParent is not set, we can't do guidance
			if (commonParentSource != null) {
				RevisionHistory parent = commonParentSource.getHistory();
				// calculate the When
				ourRelationship.setWhen(mine.getWhen(yours, parent, ourRelationship));
				// calculate the Consequences
				ourRelationship.setConsequences(mine.getConsequences(yours, parent, ourRelationship));
				// calculate the Capable
				boolean isParent = source.getShortName().equals(_prefs.getEnvironment().getParent());
				ourRelationship.setCapable(mine.getCapable(yours, parent, ourRelationship, isParent));
				// calculate the Ease
				ourRelationship.setEase(mine.getEase());
			}
			_log.trace("Relationship computed: " + relationships.get(source));
			// And finally, set the relationship to ready and update the GUI:
			Relationship current = relationships.get(source);
			current.setReady();
			publish(current);
		}
		return null;
	}

	/**
	 * Process never seems to actually execute.  
	 * By description, it is supposed to update the GUI listeners.  
	 */
	@Override
	protected void process(List<Result> chunks) {
		for (Result result : chunks) {
			_log.trace("Processing computed result: " + result);

			if (_trayListener != null)
				_trayListener.update();

			if (_clientListener != null)
				_clientListener.update();
		}
	}
}
