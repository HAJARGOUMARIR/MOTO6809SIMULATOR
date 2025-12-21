package SIMULATOR6809.CORE;

import javax.swing.JOptionPane;
import javax.swing.table.DefaultTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

//GESTIONNAIRE DE PROGRAMME

public class ProgramManager {
    private final CPU cpu;
    private final CPUView cpuView;
    private final InstructionExecutor executor;
    private final DefaultTableModel ramModel;
    private final DefaultTableModel romModel;
    private LabelManager labelManager =new LabelManager(); ;
    private List<String> programLines;
    private int currentLine;
    private boolean programLoaded;
    private Stack<CPUState> stateHistory;
    private static final int MAX_HISTORY = 100;


    private static class CPUState {
        final int a, b, x, y, u, s, pc, dp, cc;
        final int lineNumber;

        CPUState(CPU cpu, int lineNumber) {
            this.a = cpu.getA();
            this.b = cpu.getB();
            this.x = cpu.getX();
            this.y = cpu.getY();
            this.u = cpu.getU();
            this.s = cpu.getS();
            this.pc = cpu.getPC();
            this.dp = cpu.getDP();
            this.cc = cpu.getCC();
            this.lineNumber = lineNumber;
        }

        void restore(CPU cpu) {
            cpu.setA(a);
            cpu.setB(b);
            cpu.setX(x);
            cpu.setY(y);
            cpu.setU(u);
            cpu.setS(s);
            cpu.setPC(pc);
            cpu.setDP(dp);
            cpu.setCC(cc);
        }
    }

    public ProgramManager(CPU cpu, CPUView cpuView,
                          DefaultTableModel ramModel, DefaultTableModel romModel) {
        this.cpu = cpu;
        this.cpuView = cpuView;
        this.ramModel = ramModel;
        this.romModel = romModel;
        this.executor = new InstructionExecutor(cpu, ramModel, romModel);
        this.programLines = new ArrayList<>();
        this.stateHistory = new Stack<>();
        this.currentLine = 0;
        this.programLoaded = false;
    }

    public boolean loadProgram(String sourceCode) {
        if (sourceCode == null || sourceCode.trim().isEmpty()) {
            showError("Programme vide", "Le code source est vide");
            return false;
        }

        programLines.clear();
        stateHistory.clear();

        String[] lines = sourceCode.split("\\r?\\n");
        int validLineCount = 0;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // Ignorer lignes vides
            if (line.isEmpty()) {
                continue;
            }

            // Ignorer commentaires
            if (line.startsWith(";")) {
                continue;
            }

            // Supprimer commentaires en fin de ligne
            int commentIndex = line.indexOf(';');
            if (commentIndex > 0) {
                line = line.substring(0, commentIndex).trim();
            }

            programLines.add(line);
            validLineCount++;
        }

        if (validLineCount == 0) {
            showError("Programme vide", "Aucune instruction valide trouvée");
            return false;
        }

        // Auto-ajout END si manquant
        String lastLine = programLines.get(programLines.size() - 1);
        if (!lastLine.equalsIgnoreCase("END")) {
            programLines.add("END");
            showInfo("END ajouté", "La directive END a été ajoutée automatiquement");
        }

        currentLine = 0;
        programLoaded = true;

        showInfo("Programme chargé",
                String.format("%d lignes chargées avec succès", programLines.size()));

        return true;
    }

    // ASSEMBLAGE
    // Ajoutez cette variable de classe dans ProgramManager
    private int lastAssembledBytes = 0;

    // Ajoutez ce getter
    public int getLastAssembledBytes() {
        return lastAssembledBytes;
    }

    public boolean assemble() {
        if (!programLoaded) {
            showError("Aucun programme", "Chargez d'abord un programme avec loadProgram()");
            return false;
        }

        try {
            clearROM();
            executor.resetRomAddress();
            lastAssembledBytes = 0;

            int currentAddress = cpu.getPC();

            for (int i = 0; i < programLines.size(); i++) {
                String originalLine = programLines.get(i);
                String line = originalLine.trim();

                // Ignorer commentaires et lignes vides
                if (line.startsWith(";") || line.isEmpty()) continue;

                // Supprimer commentaires en fin de ligne
                int commentIndex = line.indexOf(';');
                if (commentIndex > 0) {
                    line = line.substring(0, commentIndex).trim();
                }

                if (line.equalsIgnoreCase("END")) {
                    writeROM(executor.getRomAddress(), 0x3F);
                    System.out.println("🏁 END");
                    break;
                }


                if (line.toUpperCase().startsWith("ORG")) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        currentAddress = CPU.hexToDecimal(parts[1]) & 0xFFFF;
                        executor.setRomAddress(currentAddress);
                        System.out.println("🔄 ORG $" + CPU.decimalToHex(currentAddress, 4));
                    }
                    continue;
                }

                //  GESTION DES ÉTIQUETTES
                // Cas 1: Étiquette seule sur une ligne (ex: "BOUCLE:")
                if (line.endsWith(":")) {
                    System.out.println("🏷️  Étiquette seule: " + line);
                    continue;
                }

                // Cas 2: Étiquette + instruction sur même ligne (ex: "BOUCLE: DECA")
                String instruction = line;
                if (line.contains(":")) {
                    String[] parts = line.split(":", 2);
                    if (parts.length == 2 && !parts[1].trim().isEmpty()) {
                        instruction = parts[1].trim();
                        System.out.println("🏷️  " + parts[0].trim() + ": -> " + instruction);
                    } else {
                        continue; // Étiquette seule
                    }
                }

                // Décoder l'instruction
                InstructionDecoder.DecodedInstruction instr =
                        InstructionDecoder.decode(instruction);

                if (instr == null) {
                    System.out.println("⚠️  Ligne ignorée: " + line);
                    continue;
                }

                // Résolution des branchements
                if (instr.mode == InstructionDecoder.AddressingMode.RELATIVE) {
                    String label = instr.operand.trim();
                    Integer targetAddr = labelManager.getAddress(label);

                    if (targetAddr == null) {
                        showError("Étiquette non trouvée",
                                "Ligne " + (i+1) + ": '" + label + "' non définie");
                        return false;
                    }

                    // Calcul déplacement
                    int size = executor.computeInstructionSize(instr);
                    int disp = targetAddr - (currentAddress + size);

                    if (instr.operation.startsWith("L")) {
                        // Branchement long
                        instr = new InstructionDecoder.DecodedInstruction(
                                instr.operation, instr.mode,
                                CPU.decimalToHex(disp & 0xFFFF, 4));
                    } else {
                        // Branchement court
                        instr = new InstructionDecoder.DecodedInstruction(
                                instr.operation, instr.mode,
                                CPU.decimalToHex(disp & 0xFF, 2));
                    }

                    System.out.println("🔄 " + instr.operation + " -> " + label +
                            " ($" + CPU.decimalToHex(targetAddr, 4) +
                            ") disp=" + disp);
                }

                // Émettre
                try {
                    executor.emitToROM(instr);
                    int size = executor.computeInstructionSize(instr);
                    currentAddress += size;

                    System.out.println("$" + CPU.decimalToHex(currentAddress - size, 4) +
                            ": " + instr.operation +
                            (instr.operand.isEmpty() ? "" : " " + instr.operand) +
                            " (" + size + " octets)");
                } catch (Exception e) {
                    showError("Erreur", "Ligne " + (i+1) + ": " + e.getMessage());
                    return false;
                }
            }

            lastAssembledBytes = executor.getRomAddress() - cpu.getPC();
            System.out.println("\n✅ Assemblage réussi: " + lastAssembledBytes + " octets");
            return true;

        } catch (Exception e) {
            showError("Erreur", e.getMessage());
            return false;
        }
    }

    /**
      Exécute tout le programme d'un coup
      Continue jusqu'à rencontrer END, SWI ou la fin du programme.
      Met à jour l'affichage à la fin.
     **/
    public void runProgram() {
        if (!programLoaded) {
            showError("Aucun programme", "Chargez d'abord un programme");
            return;
        }

        currentLine = 0;
        stateHistory.clear();
        int instructionCount = 0;

        try {
            while (currentLine < programLines.size()) {
                String line = programLines.get(currentLine);

                // Arrêt sur END ou SWI
                if (line.equalsIgnoreCase("END") ||
                        line.toUpperCase().startsWith("SWI")) {
                    break;
                }

                executeLine(line);
                currentLine++;
                instructionCount++;

                // Sécurité: éviter boucle infinie
                if (instructionCount > 10000) {
                    showWarning("Limite atteinte",
                            "10000 instructions exécutées. Arrêt de sécurité.");
                    break;
                }
            }

            updateDisplay();
            showInfo("Exécution terminée",
                    String.format("%d instructions exécutées", instructionCount));

        } catch (Exception e) {
            showError("Erreur d'exécution",
                    String.format("Ligne %d: %s\nInstruction: %s",
                            currentLine + 1, e.getMessage(),
                            programLines.get(currentLine)));
        }
    }

    /**
      Exécute une seule ligne (mode pas à pas)
      Sauvegarde l'état actuel dans l'historique pour permettre stepBack().
       return true si exécution réussie, false si fin du programme
     */
    public boolean step() {
        if (!programLoaded) {
            showError("Aucun programme", "Chargez d'abord un programme");
            return false;
        }

        if (currentLine >= programLines.size()) {
            showInfo("Fin du programme", "Toutes les instructions ont été exécutées");
            return false;
        }

        String line = programLines.get(currentLine);

        // Vérifier END/SWI
        if (line.equalsIgnoreCase("END") ||
                line.toUpperCase().startsWith("SWI")) {
            showInfo("Programme terminé", "Instruction de fin rencontrée");
            return false;
        }

        try {
            // Sauvegarder état AVANT exécution
            saveState();

            // Exécuter
            executeLine(line);
            currentLine++;

            // Mettre à jour affichage
            updateDisplay();

            return true;

        } catch (Exception e) {
            showError("Erreur d'exécution",
                    String.format("Ligne %d: %s\nInstruction: %s",
                            currentLine + 1, e.getMessage(), line));
            return false;
        }
    }

    /**
      Revient en arrière d'une instruction
     *
     * Restaure l'état CPU sauvegardé avant la dernière exécution.
     * Ne modifie PAS la RAM (limitation actuelle).
     *
     * @return true si retour réussi, false si impossible
     */
    public boolean stepBack() {
        if (stateHistory.isEmpty()) {
            showWarning("Début du programme",
                    "Impossible de revenir en arrière: aucun état sauvegardé");
            return false;
        }

        try {
            // Restaurer état précédent
            CPUState previousState = stateHistory.pop();
            previousState.restore(cpu);
            currentLine = previousState.lineNumber;

            updateDisplay();
            showInfo("Retour arrière",
                    String.format("État restauré à la ligne %d", currentLine + 1));

            return true;

        } catch (Exception e) {
            showError("Erreur stepBack",
                    "Impossible de restaurer l'état: " + e.getMessage());
            return false;
        }
    }


    /**
      Réinitialise complètement l'exécution
       Remet le CPU à zéro
       Efface l'historique
       Remet currentLine à 0
     */
    public void reset() {
        cpu.reset();
        currentLine = 0;
        stateHistory.clear();
        updateDisplay();

        if (programLoaded) {
            showInfo("Réinitialisation", "Programme prêt à être exécuté");
        }
    }

    /**
      Exécute une ligne de code
     */
    private void executeLine(String line) throws Exception {
        // Décoder l'instruction
        InstructionDecoder.DecodedInstruction instr =
                InstructionDecoder.decode(line);

        if (instr == null) {
            throw new Exception("Impossible de décoder l'instruction");
        }

        // Exécuter via InstructionExecutor
        executor.execute(instr);

        // Mettre à jour PC (calcul correct de la taille)
        int instructionSize = executor.computeInstructionSize(instr);
        cpu.setPC((cpu.getPC() + instructionSize) & 0xFFFF);
    }

    /**
     * Sauvegarde l'état actuel du CPU dans l'historique
     */
    private void saveState() {
        // Limiter la taille de l'historique
        if (stateHistory.size() >= MAX_HISTORY) {
            stateHistory.remove(0); // Supprimer le plus ancien
        }

        stateHistory.push(new CPUState(cpu, currentLine));
    }

    /**
     * Met à jour l'affichage de la vue CPU
     */
    private void updateDisplay() {
        if (cpuView != null) {
            cpuView.updateFromCPU(cpu);

            // Afficher l'instruction courante
            if (currentLine < programLines.size()) {
                cpuView.setInstruction(programLines.get(currentLine));
            } else {
                cpuView.setInstruction("FIN");
            }

            cpuView.repaint();
        }
    }

    //Efface complètement la ROM (remplit avec 0xFF)
    private void clearROM() {
        for (int i = 0; i < romModel.getRowCount(); i++) {
            romModel.setValueAt("FF", i, 1);
        }
    }

    //Écrit un octet dans la ROM
    public void writeROM(int address, int value) {
        try {
            if (address >= 0 && address < romModel.getRowCount()) {
                String hexValue = CPU.decimalToHex(value & 0xFF, 2);
                romModel.setValueAt(hexValue, address, 1);
            } else {
                System.err.println("Adresse ROM hors limites: " + address);
            }
        } catch (Exception e) {
            System.err.println("Erreur écriture ROM @" +
                    Integer.toHexString(address) + ": " + e.getMessage());
        }
    }

    private void showError(String title, String message) {
        JOptionPane.showMessageDialog(null, message, title,
                JOptionPane.ERROR_MESSAGE);
    }

    private void showWarning(String title, String message) {
        JOptionPane.showMessageDialog(null, message, title,
                JOptionPane.WARNING_MESSAGE);
    }

    private void showInfo(String title, String message) {
        JOptionPane.showMessageDialog(null, message, title,
                JOptionPane.INFORMATION_MESSAGE);
    }


    /**
     * @return Une copie de la liste des lignes du programme
     */
    public List<String> getProgramLines() {
        return new ArrayList<>(programLines);
    }

    /**
     * @return true si un programme est chargé
     */
    public boolean isProgramLoaded() {
        return programLoaded;
    }

    /**
     * @return L'executor d'instructions associé
     */
    public InstructionExecutor getExecutor() {
        return executor;
    }

    private boolean collectLabels() {
        if (!programLoaded) {
            System.err.println(" collectLabels: programme non chargé");
            return false;
        }

        labelManager.clear();
        int currentAddress = cpu.getPC(); // Adresse de départ

        System.out.println(" Début collection étiquettes, PC initial: $" +
                CPU.decimalToHex(currentAddress, 4));

        for (int i = 0; i < programLines.size(); i++) {
            String line = programLines.get(i);

            // Ignorer les lignes vides
            if (line.trim().isEmpty()) continue;

            // Ignorer les commentaires
            if (line.trim().startsWith(";")) continue;

            // Supprimer les commentaires en fin de ligne
            int commentIndex = line.indexOf(';');
            if (commentIndex > 0) {
                line = line.substring(0, commentIndex).trim();
            }

            // Ignorer les directives END
            if (line.equalsIgnoreCase("END")) {
                System.out.println("🏁 Directive END trouvée, fin de collecte");
                break;
            }

            // Vérifier si la ligne contient une étiquette
            String label = InstructionDecoder.extractLabel(line);
            if (label != null && !label.isEmpty()) {
                // Enregistrer l'étiquette avec son adresse
                labelManager.addLabel(label, currentAddress);
                System.out.println("✅ Étiquette: " + label +
                        " @ $" + CPU.decimalToHex(currentAddress, 4));
            }

            // Si ce n'est pas une directive ORG, calculer la taille
            if (!line.toUpperCase().startsWith("ORG")) {
                // Enlever l'étiquette pour décoder l'instruction
                String instructionOnly = InstructionDecoder.removeLabel(line);

                if (!instructionOnly.trim().isEmpty() &&
                        !instructionOnly.trim().equalsIgnoreCase("END")) {

                    // Décoder l'instruction
                    InstructionDecoder.DecodedInstruction instr =
                            InstructionDecoder.decode(instructionOnly);

                    if (instr != null) {
                        // Calculer la taille de l'instruction
                        int size = executor.computeInstructionSize(instr);
                        currentAddress += size;
                    } else {
                        // Pour les directives simples (FCB, FDB, etc.)
                        currentAddress += 1; // Taille par défaut
                    }
                }
            } else {
                // Directive ORG: changer l'adresse courante
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    String hexAddr = parts[1].replace("$", "").replace("#", "");
                    try {
                        currentAddress = Integer.parseInt(hexAddr, 16) & 0xFFFF;
                        System.out.println(" ORG vers $" + CPU.decimalToHex(currentAddress, 4));
                    } catch (NumberFormatException e) {
                        System.err.println(" Format hexadécimal invalide: " + parts[1]);
                    }
                }
            }
        }

        System.out.println(" Collection terminée. " +
                labelManager.getLabelCount() + " étiquettes.");

        return labelManager.getLabelCount() >= 0; // Toujours vrai si on arrive ici
    }
}
