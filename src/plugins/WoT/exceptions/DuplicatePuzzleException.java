/* This code is part of WoT, a plugin for Freenet. It is distributed 
 * under the GNU General Public License, version 2 (or at your option
 * any later version). See http://www.gnu.org/ for details of the GPL. */
package plugins.WoT.exceptions;


public class DuplicatePuzzleException extends RuntimeException {
	
	private static final long serialVersionUID = -1;

	public DuplicatePuzzleException(String identification) {
		super("Duplicate puzzle: " + identification);
	}
}
