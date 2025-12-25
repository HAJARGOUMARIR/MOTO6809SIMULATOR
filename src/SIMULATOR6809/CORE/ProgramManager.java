package SIMULATOR6809.CORE;

import javax.swing.JOptionPane;
import javax.swing.table.DefaultTableModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;

/* ProgrammeManager : GESTIONNAIRE DE PROGRAMME */

public class ProgramManager {
    private final CPU cpu;
    private final CPUView cpuView;
    private final InstructionExecutor executor;
    private final DefaultTableModel ramModel;
    private final DefaultTableModel romModel;
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

            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith(";")) {
                continue;
            }

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
    private int lastAssembledBytes = 0;

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

                int commentIndex = line.indexOf(';');
                if (commentIndex > 0) {
                    line = line.substring(0, commentIndex).trim();
                }

                if (line.equalsIgnoreCase("END")) {
                    writeROM(executor.getRomAddress(), 0x3F);
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

             
                InstructionDecoder.DecodedInstruction instr =
                        InstructionDecoder.decode(instruction);

                if (instr == null) {
                    continue;
                }

         }

                    int size = executor.computeInstructionSize(instr);
                    int disp = targetAddr - (currentAddress + size);

                    if (instr.operation.startsWith("L")) {
                        instr = new InstructionDecoder.DecodedInstruction(
                                instr.operation, instr.mode,
                                CPU.decimalToHex(disp & 0xFFFF, 4));
                    } else {
                        instr = new InstructionDecoder.DecodedInstruction(
                                instr.operation, instr.mode,
                                CPU.decimalToHex(disp & 0xFF, 2));
                    }

                    System.out.println("🔄 " + instr.operation + " -> " + label +
                            " ($" + CPU.decimalToHex(targetAddr, 4) +
                            ") disp=" + disp);
                }

                try {
                    executor.emitToROM(instr);
                    int size = executor.computeInstructionSize(instr);
                    currentAddress += size;


                } catch (Exception e) {
                    showError("Erreur", "Ligne " + (i+1) + ": " + e.getMessage());
                    return false;
                }
            }

            lastAssembledBytes = executor.getRomAddress() - cpu.getPC();
            return true;

        } catch (Exception e) {
            showError("Erreur", e.getMessage());
            return false;
        }
    }

    /**
     Exécute tout le programme d'un coup
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

        if (line.equalsIgnoreCase("END") ||
                line.toUpperCase().startsWith("SWI")) {
            showInfo("Programme terminé", "Instruction de fin rencontrée");
            return false;
        }

        try {
            saveState();
            executeLine(line);
            currentLine++;
            updateDisplay();
            return true;

        } catch (Exception e) {
            showError("Erreur d'exécution",
                    String.format("Ligne %d: %s\nInstruction: %s",
                            currentLine + 1, e.getMessage(), line));
            return false;
        }
    }


    public boolean stepBack() {
        if (stateHistory.isEmpty()) {
            showWarning("Début du programme",
                    "Impossible de revenir en arrière: aucun état sauvegardé");
            return false;
        }

        try {
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

    public void reset() {
        cpu.reset();
        currentLine = 0;
        stateHistory.clear();
        updateDisplay();

        if (programLoaded) {
            showInfo("Réinitialisation", "Programme prêt à être exécuté");
        }
    }


    private void executeLine(String line) throws Exception {
        InstructionDecoder.DecodedInstruction instr =
                InstructionDecoder.decode(line);

        if (instr == null) {
            throw new Exception("Impossible de décoder l'instruction");
        }

        executor.execute(instr);

        int instructionSize = executor.computeInstructionSize(instr);
        cpu.setPC((cpu.getPC() + instructionSize) & 0xFFFF);
    }


    private void saveState() {
        if (stateHistory.size() >= MAX_HISTORY) {
            stateHistory.remove(0);
        }

        stateHistory.push(new CPUState(cpu, currentLine));
    }


    private void updateDisplay() {
        if (cpuView != null) {
            cpuView.updateFromCPU(cpu);
            if (currentLine < programLines.size()) {
                cpuView.setInstruction(programLines.get(currentLine));
            } else {
                cpuView.setInstruction("FIN");
            }

            cpuView.repaint();
        }
    }

    private void clearROM() {
        for (int i = 0; i < romModel.getRowCount(); i++) {
            romModel.setValueAt("FF", i, 1);
        }
    }

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


    public List<String> getProgramLines() {
        return new ArrayList<>(programLines);
    }

    public boolean isProgramLoaded() {
        return programLoaded;
    }

    public InstructionExecutor getExecutor() {
        return executor;
    }
}
