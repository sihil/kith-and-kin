import React from 'react';
import ReactDOM from 'react-dom';
import {map} from './utils.js'

function Button(props) {
    const classes = "button is-large "+props.colour;
    return (
        <button className={classes} value={props.value} onClick={() => props.onClick()}>{props.text}</button>
    )
}

class Textbox extends React.Component {
    constructor(props) {
        super(props);
        this.state = {
            value: props.text
        };
        this.timer = null;

        this.handleChange = this.handleChange.bind(this);
    }

    update(text) {
        this.props.onClick(text)
    }

    handleChange(event) {
        const text = event.target.value;
        this.setState({value: text});
        if (this.timer) window.clearTimeout(this.timer);
        this.timer = window.setTimeout(() => this.update(text), 1200)
    }

    render() {
        return (
            <div>
                <textarea className="textarea" value={this.state.value} onChange={this.handleChange} />
            </div>
        );
    }
}

class Selection extends React.Component {
    handleChange(event, key) {
        const newAnswers = Object.assign({}, this.props.answers);
        newAnswers[key] = event.target.checked;
        this.props.onUpdate(newAnswers);
    }
    render() {
        const possibleAnswers = this.props.question.answers;
        const optionsHtml = possibleAnswers.map((answer, index) => {
            const answers = this.props.answers;
            const selected = answers[answer.key];
            return (
                <p key={index} className="control">
                    <label className="checkbox">
                        <input
                            type="checkbox"
                            defaultChecked={selected}
                            onChange={(event) => this.handleChange(event, answer.key)}
                        />
                        {answer.text}
                    </label>
                </p>
            );
        });
        return(
            <div className="selection">
                {optionsHtml}
            </div>
        );
    }
}

class Price extends React.Component {
    amount(currencyInt) {
        const sign = (currencyInt < 0) ? "-" : "";
        const initialString = Math.abs(currencyInt).toString();
        const minimalString = (initialString.length < 3) ? "0" * (3-initialString.length) + initialString : initialString;
        console.log(minimalString);
        const pounds = minimalString.slice(0, minimalString.length - 2);
        const pence = minimalString.slice(minimalString.length-2, minimalString.length);
        return `${sign}£${pounds}.${pence}`
    }

    render() {
        const price = this.props.price;
        const subTotal = price.map((p) => p.subTotal).reduce((a, b) => a + b, 0);
        const showBreakdown = price.length > 1 || price.filter((a) => a.english).length > 0;
        const breakdown = price.map((p) => {
            const english = p.english ? " "+p.english : "";
            return this.amount(p.amount)+english+" ("+p.desc+")";
        }).join(" + ");
        const maybeBreakdown = showBreakdown ? " = "+breakdown+"" : "";
        const soleDesc = (price.length == 1 && !price[0].english) ? price[0].desc+" " : "";
        if (subTotal > 0) {
            return(
                <div>
                    <p>{soleDesc}{this.amount(subTotal)}{maybeBreakdown}</p>
                </div>
            );
        } else return null;
    }
}

class Question extends React.Component {
    renderAnswer(questionType) {
        const answers = this.props.question.answers;
        switch (questionType) {
            case "multipleChoice":
                return answers.map((answer, index) => {
                    const colour = (this.props.answer === answer.key) ? "is-success" : "";
                    return (
                        <Button key={index} colour={colour} text={answer.text}
                                onClick={() => this.props.onAnswer(answer.key)}/>
                    );
                });
            case "text":
                return (
                    <Textbox text={this.props.answer} onClick={(newAnswer) => this.props.onAnswer(newAnswer)}/>
                );
            case "selection":
                return (
                    <Selection question={this.props.question} answers={this.props.answer} onUpdate={(newAnswers) => this.props.onAnswer(newAnswers)} />
                );
        }
    }

    render() {
        const question = this.props.question;
        const needHeading = question.helpText ? "" : "heading";
        const htmlQuestion = <h2 className={needHeading}>{question.question}</h2>;
        const htmlAnswer = this.renderAnswer(question.questionType);
        const price = this.props.price;
        return (
            <div className="question">
                {htmlQuestion}
                {question.helpText &&
                    <p>{question.helpText}</p>
                }
                {htmlAnswer}
                {price &&
                    <Price price={price}/>
                }
            </div>
        )
    }
}

class Rsvp extends React.Component {
    constructor() {
        super();
        this.state = {
            questions: null,
            startKey: null,
            answers: {},
            unsent: true,
            prices: {}
        }
    }
    findAnswer(question, answerKey) {
        return question.answers.find((answer) => { return answer.key === answerKey });
    }
    componentDidMount() {
        // get questions
        fetch("/rsvp/questions", {
            credentials: 'include'
        }).then((response) => {
            return response.json().then((json) => {
                const answerMap = json.answers;
                this.setState({
                    questions: json.questions, startKey: json.startPage, answers: answerMap,
                    unsent: json.unsent, prices: json.prices
                });
            });
        });
    }
    update(answers, complete) {
        return fetch("/rsvp/update?complete="+complete, {
            credentials: 'include',
            headers: {
                'Content-Type': 'application/json',
                'Csrf-Token': window.csrf
            },
            method: 'POST',
            body: JSON.stringify(answers)
        })
    }
    onAnswer(question, answer) {
        const answers = {};
        answers[question.key] = answer;
        this.update(answers, false).then((res) => {
            if (res.status == 200) {
                this.setState((previous) => {
                    const newAnswer = {};
                    newAnswer[question.key] = answer;
                    return {answers: Object.assign({}, previous.answers, newAnswer), unsent: true};
                });
                res.json().then((json) => {
                    if (json.questions) {
                        this.setState({questions: json.questions});
                    }
                    if (json.prices) {
                        this.setState({prices: json.prices});
                    }
                })
            }
        });
    }
    nextQuestion(question, answerObject) {
        switch(question.questionType) {
            case "multipleChoice":
                return answerObject.nextQuestion;
            case "text":
                return question.nextQuestion;
            case "selection":
                return question.nextQuestion;
        }
    }
    questionAnswers(key) {
        const question = this.state.questions[key];
        if (question) {
            const answerKey = this.state.answers[question.key];
            const price = this.state.prices.breakdown[question.key];
            if (answerKey || question.optional) {
                const answerObject = this.findAnswer(question, answerKey);
                const nextQuestion = this.nextQuestion(question, answerObject);
                return [{question: question, answer: answerKey, price: price}].concat(this.questionAnswers(nextQuestion));
            } else {
                return [{question: question, price: price}];
            }
        } else return [];
    }
    renderQuestion(question, answer, price) {
        return (
            <Question key={question.key} question={question} answer={answer} price={price} onAnswer={(answer) => this.onAnswer(question, answer)}/>
        );
    }
    finishRsvp() {
        this.update(this.state.answers, true).then((res) => {
            if (res.status == 200) {
                window.location = '/rsvp/complete'
            }
        })
    }
    render() {
        if (this.state.questions) {
            const questionList = this.questionAnswers(this.state.startKey);
            const questionElements = questionList.map((question) => {
                return this.renderQuestion(question.question, question.answer, question.price);
            });
            const bottomQuestion = questionList[questionList.length-1];
            const terminusQuestion = bottomQuestion.question.nextQuestion === undefined;
            const finished = terminusQuestion && (bottomQuestion.answer || bottomQuestion.question.optional);
            const unsent = this.state.unsent;
            return (
                <div>
                    {questionElements}
                    {finished &&
                        <div>
                            <h2 className="heading">You're finished!</h2>
                            {unsent && <Button colour="is-danger" onClick={() => this.finishRsvp()} text="Send RSVP"/>}
                        </div>
                    }
                </div>
            );
        } else {
            return (
                <p>Loading RSVP information...</p>
            );
        }
    }
}

ReactDOM.render(
    <Rsvp />,
    document.getElementById('container')
);